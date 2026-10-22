package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.DiveLogParser
import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.GasSwitch
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.Sample
import no.synth.divelog.core.model.units.ZERO_CELSIUS_MK

/**
 * Parses one old-Vyper dive record ([SuuntoVyperDump]) into an [IncomingDive]. Layout
 * as in libdivecomputer's suunto_vyper_parser.c:
 *
 * | Offset | Meaning |
 * |---|---|
 * | 3 | sample interval, seconds |
 * | 4 | mode flags: `0x40` gauge, low nibble personal adjustment |
 * | 5 | tank pressure at the start, bar / 2 |
 * | 6 | O2 percent (0 means air) |
 * | 8 | surface temperature, signed Celsius |
 * | 9..13 | start date/time: year (from 1900, below 90 from 2000), month, day, hour, minute |
 * | 14.. | samples until the `0x80` end-of-dive marker |
 * | marker + 1 | minimum temperature, signed Celsius |
 * | marker + 3 | tank pressure at the end, bar / 2 |
 *
 * Each sample byte outside `0x79..0x87` is a signed depth delta in feet (positive is
 * deeper), one per interval. Bytes in that range are events; `0x87` is a gas change
 * followed by the new O2 percent.
 */
class SuuntoVyperParser : DiveLogParser {
    override val formatId: String = SuuntoVyperDump.FORMAT_ID

    override fun parse(raw: RawDive): IncomingDive {
        val d = raw.data
        if (d.size < MIN_SIZE) throw ProtocolException("Vyper record too small: ${d.size} bytes")

        val interval = d.u8(3)
        val gauge = d.u8(4) and 0x40 != 0
        val startPressureMbar = d.u8(5) * 2 * 1_000
        val oxygen = mutableListOf(d.u8(6).takeIf { it != 0 } ?: 21)

        val samples = mutableListOf(Sample(timeOffsetSeconds = 0, depthMm = 0))
        val events = mutableListOf<Event>()
        var depthFt = 0
        var maxDepthFt = 0
        var time = 0
        var nsamples = 0
        var complete = true
        var activeGas = 0
        var offset = HEADER
        while (offset < d.size && d.u8(offset) != SuuntoVyperDump.END_OF_DIVE) {
            val value = d.u8(offset++)
            if (complete) {
                time += interval
                complete = false
            }
            if (value !in EVENT_RANGE) {
                depthFt += d[offset - 1].toInt() // signed
                if (depthFt > maxDepthFt) maxDepthFt = depthFt
                nsamples++
                samples += Sample(
                    timeOffsetSeconds = time,
                    depthMm = feetToMillimetres(depthFt),
                    activeGasIndex = if (gauge) null else activeGas,
                )
                complete = true
                continue
            }
            val type = when (value) {
                0x7A -> EventType.ASCENT_RATE
                0x7B, 0x7F -> EventType.WARNING // violation, ceiling broken
                0x7C -> EventType.BOOKMARK
                0x7D -> EventType.SURFACE
                0x7E, 0x81 -> EventType.DECO // deco, safety stop
                0x87 -> {
                    if (offset >= d.size) throw ProtocolException("Vyper record: truncated gas change")
                    val o2 = d.u8(offset++)
                    activeGas = oxygen.indexOf(o2).takeIf { it >= 0 } ?: oxygen.size.also { oxygen += o2 }
                    events += Event(time, EventType.GAS_SWITCH, value = GasSwitch.value(o2, 0))
                    null
                }
                else -> null
            }
            if (type != null) events += Event(time, type)
        }

        val marker = offset
        if (marker + 4 >= d.size) throw ProtocolException("Vyper record: no end-of-dive marker")
        val endPressureMbar = d.u8(marker + 3) * 2 * 1_000

        if (complete) time += interval
        samples += Sample(timeOffsetSeconds = time, depthMm = 0)
        if (startPressureMbar > 0 || endPressureMbar > 0) {
            samples[0] = samples[0].copy(tankPressuresMbar = mapOf(0 to startPressureMbar))
            samples[samples.lastIndex] = samples.last().copy(tankPressuresMbar = mapOf(0 to endPressureMbar))
        }

        val submerged = samples.mapNotNull { it.depthMm }.filter { it > 0 }
        val p = DATETIME_OFFSET
        val year = d.u8(p) + if (d.u8(p) < 90) 2000 else 1900
        return IncomingDive(
            number = null,
            startEpochSeconds = SuuntoVyper2Parser.civilToEpochSeconds(year, d.u8(p + 1), d.u8(p + 2), d.u8(p + 3), d.u8(p + 4), 0),
            utcOffsetSeconds = 0, // device stores local wall-clock time with no zone
            durationSeconds = nsamples * interval,
            maxDepthMm = feetToMillimetres(maxDepthFt).takeIf { it > 0 },
            meanDepthMm = if (submerged.isNotEmpty()) submerged.sum() / submerged.size else null,
            waterTempMk = celsiusToMilliKelvin(d[marker + 1].toInt()),
            airTempMk = celsiusToMilliKelvin(d[8].toInt()),
            rawData = raw.data,
            rawFormatId = raw.formatId,
            fingerprint = raw.fingerprint,
            samples = samples,
            events = events,
            gases = if (gauge) emptyList() else oxygen.map { GasMix(o2Permille = it * 10, hePermille = 0) },
        )
    }

    companion object {
        private const val HEADER = 14
        private const val MIN_SIZE = 18
        private const val DATETIME_OFFSET = 9
        private val EVENT_RANGE = 0x79..0x87
        private const val MM_PER_FOOT_X10 = 3048 // tenths of a millimetre per foot

        private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF

        private fun feetToMillimetres(feet: Int): Int = feet * MM_PER_FOOT_X10 / 10

        private fun celsiusToMilliKelvin(celsius: Int): Int = celsius * 1_000 + ZERO_CELSIUS_MK
    }
}
