package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.DiveLogParser
import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.Sample
import no.synth.divelog.core.model.units.ZERO_CELSIUS_MK

/**
 * Parses one old-Vyper dive record into an [IncomingDive].
 *
 * A record (see [SuuntoVyperDump]) is a three byte head followed by signed
 * delta-depth samples:
 *
 * | Offset | Meaning |
 * |---|---|
 * | 0 | `OLF` / dive-mode byte (bit 7 selects OTU over CNS) |
 * | 1 | tank pressure at the end of the dive, bar / 2 (0 on hoseless models) |
 * | 2 | water temperature at the end of the dive, signed Celsius |
 * | 3.. | one signed delta-depth byte per sample interval, stored end-of-dive first |
 *
 * A delta is `previousDepth - currentDepth` in feet, so a descent is negative and
 * an ascent positive. Samples are stored in reverse time, so the deltas are read
 * back-to-front to rebuild the profile from the surface.
 *
 * The Zoop records depth and temperature only. The sample interval is not in the
 * per-dive record (it lives in the device header), so it is supplied by the
 * protocol; [DEFAULT_SAMPLE_INTERVAL_SECONDS] is the Zoop default. Start time is
 * not present in this minimal record and is left at 0 until the header date/time
 * fields are confirmed against a capture; the fingerprint stands in for identity.
 */
class SuuntoVyperParser(
    private val sampleIntervalSeconds: Int = DEFAULT_SAMPLE_INTERVAL_SECONDS,
) : DiveLogParser {
    override val formatId: String = SuuntoVyperDump.FORMAT_ID

    override fun parse(raw: RawDive): IncomingDive {
        val d = raw.data
        if (d.size < HEAD) throw ProtocolException("Vyper record too small: ${d.size} bytes")

        val endTempC = d[2].toInt() // signed Celsius
        val waterTempMk = celsiusToMilliKelvin(endTempC)

        // Deltas are stored end-first; reverse to walk forward from the surface.
        val deltasForward = d.copyOfRange(HEAD, d.size).reversedArray()

        val samples = ArrayList<Sample>(deltasForward.size + 1)
        var depthFt = 0
        var maxDepthMm = 0
        var depthSumMm = 0L
        var submergedCount = 0

        // Surface start sample.
        samples += Sample(timeOffsetSeconds = 0, depthMm = 0, temperatureMk = waterTempMk)

        for ((index, delta) in deltasForward.withIndex()) {
            depthFt -= delta.toInt() // delta = prev - current, so current = prev - delta
            if (depthFt < 0) depthFt = 0
            val depthMm = feetToMillimetres(depthFt)
            if (depthMm > maxDepthMm) maxDepthMm = depthMm
            if (depthMm > 0) {
                depthSumMm += depthMm
                submergedCount++
            }
            samples += Sample(
                timeOffsetSeconds = (index + 1) * sampleIntervalSeconds,
                depthMm = depthMm,
                temperatureMk = waterTempMk,
            )
        }

        val durationSeconds = deltasForward.size * sampleIntervalSeconds
        val meanDepthMm = if (submergedCount > 0) (depthSumMm / submergedCount).toInt() else null

        return IncomingDive(
            number = null,
            startEpochSeconds = 0L, // not in the minimal record; see class note
            utcOffsetSeconds = 0,
            durationSeconds = durationSeconds,
            maxDepthMm = if (maxDepthMm > 0) maxDepthMm else null,
            meanDepthMm = meanDepthMm,
            waterTempMk = waterTempMk,
            rawData = raw.data,
            rawFormatId = raw.formatId,
            fingerprint = raw.fingerprint,
            samples = samples,
        )
    }

    companion object {
        const val DEFAULT_SAMPLE_INTERVAL_SECONDS = 20
        private const val HEAD = 3
        private const val MM_PER_FOOT = 3048 // tenths of a millimetre per foot / 10

        private fun feetToMillimetres(feet: Int): Int = feet * MM_PER_FOOT / 10

        private fun celsiusToMilliKelvin(celsius: Int): Int = celsius * 1_000 + ZERO_CELSIUS_MK
    }
}
