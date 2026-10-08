package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.DiveLogParser
import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.GasSwitch
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.Sample

/**
 * Parses the older Predator log format shared by the Predator and Petrel 1, and the
 * record form Shearwater Cloud stores dives in.
 *
 * Block layout: a 128-byte opening block, a run of fixed-size sample records, then a
 * 128-byte closing block. The Predator stores 16-byte samples; the Petrel 1 stores
 * 32-byte records whose first 16 bytes hold the same fields (the rest is extra data
 * we ignore). The sample stride is chosen from the raw dive's format id. Depths are
 * tenths of a metre or foot per the opening block's units flag; temperature is a
 * signed whole degree Celsius. Field offsets were confirmed against real captures.
 *
 * Record layout ([PNF_FORMAT_ID]): a stream of 32-byte records, each led by a type
 * byte. Opening (0x10) and closing (0x20) records are the first 32 bytes of those
 * blocks with byte 0 replaced by the type; a sample record (0x01) is the type byte
 * followed by the sample fields, so they sit one byte later. The closing record holds
 * the duration in seconds. Confirmed against 302 Shearwater Cloud dives.
 */
class PredatorParser(
    private val sampleIntervalSeconds: Int = DEFAULT_SAMPLE_INTERVAL_SECONDS,
) : DiveLogParser {
    override val formatId: String = PredatorDump.FORMAT_ID

    override fun parse(raw: RawDive): IncomingDive {
        val d = raw.data
        val layout = if (raw.formatId == PNF_FORMAT_ID) recordLayout(d) else blockLayout(d, raw.formatId)
        val header = layout.header
        val imperial = header[UNITS_OFFSET].toInt() == 1
        val number = be16(header, NUMBER_OFFSET)
        val startEpoch = be32(header, START_TIME_OFFSET)
        val durationSeconds = layout.durationSeconds

        val samples = ArrayList<Sample>()
        val events = ArrayList<Event>()
        var previousGas: Pair<Int, Int>? = null

        for ((index, offset) in layout.sampleOffsets.withIndex()) {
            val time = index * sampleIntervalSeconds
            val depthMm = toMillimetres(be16(d, offset + S_DEPTH), imperial)
            // The sample temperature follows the dive's unit flag: Fahrenheit for
            // an imperial dive, Celsius for a metric one.
            val tempMk = if (imperial) {
                fahrenheitToMilliKelvin(signed(d[offset + S_TEMP]))
            } else {
                celsiusToMilliKelvin(signed(d[offset + S_TEMP]))
            }
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
                events += Event(timeOffsetSeconds = time, type = EventType.GAS_SWITCH, value = GasSwitch.value(o2, he))
                previousGas = gas
            }

        }

        // Drop the surface tail the computer keeps recording after surfacing, so the
        // profile span and mean depth reflect the actual dive (to final ascent). The
        // closing-block duration already ends at surfacing.
        while (samples.size > 1 && (samples.last().depthMm ?: 0) == 0) {
            samples.removeAt(samples.lastIndex)
        }
        val lastSampleTime = samples.lastOrNull()?.timeOffsetSeconds ?: 0
        events.retainAll { it.timeOffsetSeconds <= lastSampleTime }

        var maxDepthMm = 0
        var depthSumMm = 0L
        var minTempMk: Int? = null
        for (s in samples) {
            val sampleDepthMm = s.depthMm ?: 0
            if (sampleDepthMm > maxDepthMm) maxDepthMm = sampleDepthMm
            depthSumMm += sampleDepthMm
            // Water temperature from submerged samples only.
            if (sampleDepthMm > 0) {
                val t = s.temperatureMk
                if (t != null) minTempMk = minTempMk?.let { minOf(it, t) } ?: t
            }
        }
        val meanDepthMm = if (samples.isNotEmpty()) (depthSumMm / samples.size).toInt() else null

        return IncomingDive(
            number = number,
            startEpochSeconds = startEpoch,
            utcOffsetSeconds = 0, // device stores local time; offset refined later
            durationSeconds = if (durationSeconds > 0) durationSeconds else samples.size * sampleIntervalSeconds,
            maxDepthMm = if (samples.isNotEmpty()) maxDepthMm else null,
            meanDepthMm = meanDepthMm,
            waterTempMk = minTempMk,
            rawData = raw.data,
            rawFormatId = raw.formatId,
            fingerprint = raw.fingerprint,
            samples = samples,
            events = events,
            // Every mix the samples report is announced by a switch, the first one included.
            gases = events.filter { it.type == EventType.GAS_SWITCH }.mapNotNull { it.value }.distinct()
                .map { GasMix(o2Permille = GasSwitch.o2Percent(it) * 10, hePermille = GasSwitch.hePercent(it) * 10) },
        )
    }

    /** Where a layout keeps the opening fields, its samples (each at the sample-field base) and duration. */
    private class Layout(val header: ByteArray, val sampleOffsets: List<Int>, val durationSeconds: Int)

    private fun blockLayout(d: ByteArray, formatId: String): Layout {
        if (d.size < 2 * BLOCK) throw ProtocolException("Predator record too small: ${d.size} bytes")
        val stride = if (formatId == PETREL_FORMAT_ID) PETREL_SAMPLE_STRIDE else SAMPLE_SIZE
        // The closing block is the first 128-aligned block (after the opening one)
        // that starts with the close marker. A ring-extracted Predator dive ends
        // exactly at its closing block, but a Petrel's per-dive blob carries one or
        // more trailing blocks after it, so assuming the last block is the closer
        // reads the close marker as a 0xFFFE depth sample and padding as duration.
        val closing = closingBlockOffset(d)
        val offsets = generateSequence(BLOCK) { it + stride }.takeWhile { it + SAMPLE_SIZE <= closing }.toList()
        return Layout(d, offsets, be16(d, closing + CLOSE_DURATION_OFFSET) * 60)
    }

    private fun recordLayout(d: ByteArray): Layout {
        val records = (0 until d.size / RECORD).map { it * RECORD }
        val opening = records.firstOrNull { d[it] == REC_OPENING }
            ?: throw ProtocolException("Shearwater record log without an opening record")
        val closing = records.firstOrNull { d[it] == REC_CLOSING }
        val samples = records.filter { d[it] == REC_SAMPLE }.map { it + 1 }
        val duration = closing?.let { be24(d, it + REC_CLOSE_DURATION_SECONDS) } ?: 0
        return Layout(d.copyOfRange(opening, opening + RECORD), samples, duration)
    }

    /**
     * Offset of the dive's closing block: the first block boundary after the
     * opening block whose first two bytes are the close marker. Falls back to the
     * last block if no marker is found.
     */
    private fun closingBlockOffset(d: ByteArray): Int {
        var offset = BLOCK
        while (offset + BLOCK <= d.size) {
            if (d[offset] == CLOSE_MARKER_HI && d[offset + 1] == CLOSE_MARKER_LO) return offset
            offset += BLOCK
        }
        return d.size - BLOCK
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

        /** Format id for Petrel 1 dives, whose sample records are 32 bytes wide. */
        const val PETREL_FORMAT_ID = "shearwater-petrel-log"

        /** Format id for dives in Shearwater's typed 32-byte record form (Shearwater Cloud). */
        const val PNF_FORMAT_ID = "shearwater-pnf"

        private const val BLOCK = 128
        private const val SAMPLE_SIZE = 16
        private const val PETREL_SAMPLE_STRIDE = 32

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

        // Typed records
        private const val RECORD = 32
        private const val REC_SAMPLE = 0x01.toByte()
        private const val REC_OPENING = 0x10.toByte()
        private const val REC_CLOSING = 0x20.toByte()
        private const val REC_CLOSE_DURATION_SECONDS = 6

        // Closing block (relative to its start)
        private const val CLOSE_DURATION_OFFSET = 6
        private const val CLOSE_MARKER_HI = 0xFF.toByte()
        private const val CLOSE_MARKER_LO = 0xFE.toByte()

        private const val MM_PER_TENTH_METRE = 100
        private const val MM_PER_TENTH_FOOT = 30.48

        private fun be16(d: ByteArray, o: Int): Int =
            ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)

        private fun be24(d: ByteArray, o: Int): Int =
            ((d[o].toInt() and 0xFF) shl 16) or ((d[o + 1].toInt() and 0xFF) shl 8) or (d[o + 2].toInt() and 0xFF)

        private fun be32(d: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (d[o + i].toLong() and 0xFF)
            return v
        }

        private fun signed(b: Byte): Int = b.toInt()

        private fun celsiusToMilliKelvin(celsius: Int): Int = celsius * 1_000 + 273_150

        private fun fahrenheitToMilliKelvin(fahrenheit: Int): Int =
            (fahrenheit - 32) * 5_000 / 9 + 273_150

    }
}
