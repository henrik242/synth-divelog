package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.DiveLogParser
import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.Sample

/**
 * Parses the older Predator log format shared by the Predator and Petrel 1.
 *
 * Layout: a 128-byte opening block, a run of fixed 16-byte samples, then a
 * 128-byte closing block. Depths are tenths of a metre or foot per the opening
 * block's units flag; temperature is a signed whole degree Celsius. Field
 * offsets were confirmed against real captures.
 */
class PredatorParser(
    private val sampleIntervalSeconds: Int = DEFAULT_SAMPLE_INTERVAL_SECONDS,
) : DiveLogParser {
    override val formatId: String = PredatorDump.FORMAT_ID

    override fun parse(raw: RawDive): IncomingDive {
        val d = raw.data
        if (d.size < 2 * BLOCK) {
            throw ProtocolException("Predator record too small: ${d.size} bytes")
        }

        val imperial = d[UNITS_OFFSET].toInt() == 1
        val number = be16(d, NUMBER_OFFSET)
        val startEpoch = be32(d, START_TIME_OFFSET)
        val durationSeconds = be16(d, d.size - BLOCK + CLOSE_DURATION_OFFSET) * 60

        val sampleRegionEnd = d.size - BLOCK
        val samples = ArrayList<Sample>()
        val events = ArrayList<Event>()
        var maxDepthMm = 0
        var depthSumMm = 0L
        var minTempMk: Int? = null
        var previousGas: Pair<Int, Int>? = null

        var offset = BLOCK
        var index = 0
        while (offset + SAMPLE_SIZE <= sampleRegionEnd) {
            val time = index * sampleIntervalSeconds
            val depthMm = toMillimetres(be16(d, offset + S_DEPTH), imperial)
            val tempMk = celsiusToMilliKelvin(signed(d[offset + S_TEMP]))
            val ndlSeconds = (d[offset + S_NDL].toInt() and 0xFF).let { if (it == 0) null else it * 60 }
            val stopDepthMm = be16(d, offset + S_STOP_DEPTH).let { if (it == 0) null else toMillimetres(it, imperial) }
            val stopTimeSeconds = be16(d, offset + S_TTS).let { if (it == 0) null else it * 60 }
            val o2 = d[offset + S_O2].toInt() and 0xFF
            val he = d[offset + S_HE].toInt() and 0xFF
            val ppO2Mbar = sensorPpO2Mbar(d, offset)

            samples += Sample(
                timeOffsetSeconds = time,
                depthMm = depthMm,
                temperatureMk = tempMk,
                ppO2Mbar = ppO2Mbar,
                ndlSeconds = ndlSeconds,
                stopDepthMm = stopDepthMm,
                stopTimeSeconds = stopTimeSeconds,
            )

            val gas = o2 to he
            if (o2 > 0 && gas != previousGas) {
                events += Event(timeOffsetSeconds = time, type = EventType.GAS_SWITCH, value = encodeGas(o2, he))
                previousGas = gas
            }

            if (depthMm > maxDepthMm) maxDepthMm = depthMm
            depthSumMm += depthMm
            // Water temperature from submerged samples only; surface samples
            // often report 0 and would otherwise skew the minimum.
            if (depthMm > 0) {
                minTempMk = minTempMk?.let { minOf(it, tempMk) } ?: tempMk
            }
            offset += SAMPLE_SIZE
            index++
        }

        val meanDepthMm = if (index > 0) (depthSumMm / index).toInt() else null

        return IncomingDive(
            number = number,
            startEpochSeconds = startEpoch,
            utcOffsetSeconds = 0, // device stores local time; offset refined later
            durationSeconds = if (durationSeconds > 0) durationSeconds else index * sampleIntervalSeconds,
            maxDepthMm = if (index > 0) maxDepthMm else null,
            meanDepthMm = meanDepthMm,
            waterTempMk = minTempMk,
            rawData = raw.data,
            rawFormatId = raw.formatId,
            fingerprint = raw.fingerprint,
            samples = samples,
            events = events,
        )
    }

    private fun toMillimetres(tenths: Int, imperial: Boolean): Int =
        if (imperial) (tenths * MM_PER_TENTH_FOOT).toInt() else tenths * MM_PER_TENTH_METRE

    private fun sensorPpO2Mbar(d: ByteArray, sampleOffset: Int): Int? {
        // Raw sensor millivolt-style reading; kept as a coarse ppO2 hint in mbar.
        val raw = d[sampleOffset + S_SENSOR0].toInt() and 0xFF
        return if (raw == 0) null else raw * 10
    }

    companion object {
        const val DEFAULT_SAMPLE_INTERVAL_SECONDS = 10

        private const val BLOCK = 128
        private const val SAMPLE_SIZE = 16

        // Opening block
        private const val NUMBER_OFFSET = 2
        private const val UNITS_OFFSET = 8
        private const val START_TIME_OFFSET = 12

        // Sample fields
        private const val S_DEPTH = 0
        private const val S_STOP_DEPTH = 2
        private const val S_TTS = 4
        private const val S_O2 = 7
        private const val S_HE = 8
        private const val S_NDL = 9
        private const val S_SENSOR0 = 12
        private const val S_TEMP = 13

        // Closing block (relative to its start)
        private const val CLOSE_DURATION_OFFSET = 6

        private const val MM_PER_TENTH_METRE = 100
        private const val MM_PER_TENTH_FOOT = 30.48

        private fun be16(d: ByteArray, o: Int): Int =
            ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)

        private fun be32(d: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (d[o + i].toLong() and 0xFF)
            return v
        }

        private fun signed(b: Byte): Int = b.toInt()

        private fun celsiusToMilliKelvin(celsius: Int): Int = celsius * 1_000 + 273_150

        private fun encodeGas(o2: Int, he: Int): Long = (o2.toLong() shl 8) or he.toLong()
    }
}
