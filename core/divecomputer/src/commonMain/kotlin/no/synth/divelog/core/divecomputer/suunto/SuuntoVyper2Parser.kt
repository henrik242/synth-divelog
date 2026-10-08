package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.DiveLogParser
import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.u16le
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.GasSwitch
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.Sample
import no.synth.divelog.core.model.units.ZERO_CELSIUS_MK

/**
 * Parses one Vyper2-family dive record into an [IncomingDive]. The record is the dive
 * data [SuuntoVyper2Dump] hands over, i.e. the bytes after the four byte prev/next head.
 *
 * Only the HelO2 (model 0x15) field and profile layout is implemented; other models
 * in the family place the same fields at different offsets. [model] selects the
 * layout and defaults to HelO2.
 *
 * Fixed-position summary fields (all little-endian unless noted):
 *
 * | Offset | Meaning |
 * |---|---|
 * | 0x09 | max depth, centimetres |
 * | 0x0D | dive time, minutes (HelO2) |
 * | 0x17 | date/time, seven bytes (HelO2) |
 * | 0x1E | sample interval, seconds (HelO2) |
 * | 0x1F | gas mode byte (HelO2) |
 * | 0x26 | initial gas-mix index (HelO2) |
 * | 0x54 | gas-mix table, eight entries of six bytes (HelO2) |
 *
 * The sample configuration and the profile follow the gas-mix table. The profile is
 * a flat stream: each tick emits the parameters whose interval divides the tick,
 * with occasional event groups flagged by a running marker index.
 *
 * The mixes used become [IncomingDive.gases]; dive mode is parsed (to resolve gas
 * switches) but not surfaced. Deco stop depth and time are not in this format, so deco
 * shows only as events at state changes.
 */
class SuuntoVyper2Parser(
    private val model: Int = HELO2,
) : DiveLogParser {
    override val formatId: String = SuuntoVyper2Dump.FORMAT_ID

    override fun parse(raw: RawDive): IncomingDive {
        if (model != HELO2) {
            throw ProtocolException("Vyper2 parser only implements the HelO2 layout; model 0x${model.toString(16)} is unwired")
        }
        val d = raw.data
        requireSize(d, DATETIME_OFFSET + 7)

        val startEpoch = parseDateTime(d)
        val maxDepthMm = u16le(d, MAXDEPTH_OFFSET) * 10 // centimetres -> millimetres
        val durationSeconds = u16le(d, DIVETIME_OFFSET) * 60

        val gas = parseGasMixes(d)
        val profile = parseProfile(d, gas)

        val waterTempMk = profile.samples.mapNotNull { it.temperatureMk }.minOrNull()
        val submerged = profile.samples.mapNotNull { it.depthMm }.filter { it > 0 }
        val meanDepthMm = if (submerged.isNotEmpty()) submerged.sum() / submerged.size else null

        return IncomingDive(
            number = null,
            startEpochSeconds = startEpoch,
            utcOffsetSeconds = 0, // device stores local wall-clock time with no zone
            durationSeconds = durationSeconds,
            maxDepthMm = if (maxDepthMm > 0) maxDepthMm else null,
            meanDepthMm = meanDepthMm,
            waterTempMk = waterTempMk,
            airTempMk = null, // no distinct air-temperature field in this record
            rawData = raw.data,
            rawFormatId = raw.formatId,
            fingerprint = raw.fingerprint,
            samples = profile.samples,
            events = profile.events,
            gases = gasesUsed(gas, profile.samples),
        )
    }

    private fun parseDateTime(d: ByteArray): Long {
        val p = DATETIME_OFFSET
        val hour = d[p].toInt() and 0xFF
        val minute = d[p + 1].toInt() and 0xFF
        val second = d[p + 2].toInt() and 0xFF
        val year = (d[p + 3].toInt() and 0xFF) or ((d[p + 4].toInt() and 0xFF) shl 8)
        val month = d[p + 5].toInt() and 0xFF
        val day = d[p + 6].toInt() and 0xFF
        return civilToEpochSeconds(year, month, day, hour, minute, second)
    }

    /** The mixes the dive breathed (the initial one, then each switched to), in order. */
    private fun gasesUsed(gas: GasInfo, samples: List<Sample>): List<GasMix> =
        (listOf(gas.initialIndex) + samples.mapNotNull { it.activeGasIndex })
            .distinct()
            .filter { it in gas.oxygen.indices && gas.oxygen[it] > 0 }
            .map { GasMix(o2Permille = gas.oxygen[it] * 10, hePermille = gas.helium[it] * 10) }

    /** O2/He percentages per mix plus the initial mix index. */
    private class GasInfo(val oxygen: IntArray, val helium: IntArray, val mode: Int, val initialIndex: Int)

    private fun parseGasMixes(d: ByteArray): GasInfo {
        val mode = d[GASMODE_OFFSET].toInt() and 0xFF
        return when (mode) {
            MODE_GAUGE, MODE_FREEDIVE -> GasInfo(IntArray(0), IntArray(0), mode, 0)
            MODE_AIR -> GasInfo(intArrayOf(21), intArrayOf(0), mode, 0)
            else -> {
                requireSize(d, GASMIX_OFFSET + GASMIX_COUNT * GASMIX_STRIDE)
                val o2 = IntArray(GASMIX_COUNT)
                val he = IntArray(GASMIX_COUNT)
                for (i in 0 until GASMIX_COUNT) {
                    o2[i] = d[GASMIX_OFFSET + GASMIX_STRIDE * i + 1].toInt() and 0xFF
                    he[i] = d[GASMIX_OFFSET + GASMIX_STRIDE * i + 2].toInt() and 0xFF
                }
                GasInfo(o2, he, mode, d[INITIAL_GASMIX_OFFSET].toInt() and 0xFF)
            }
        }
    }

    private class Profile(val samples: List<Sample>, val events: List<Event>)

    private class SampleParam(val type: Int, val interval: Int, val divisor: Int, val size: Int)

    private fun parseProfile(d: ByteArray, gas: GasInfo): Profile {
        // Sample configuration sits right after the gas-mix table.
        val config = GASMIX_OFFSET + GASMIX_COUNT * GASMIX_STRIDE
        requireSize(d, config + 1)
        val nparams = d[config].toInt() and 0xFF
        if (nparams == 0 || nparams > MAX_PARAMS) {
            throw ProtocolException("Vyper2: invalid sample parameter count $nparams")
        }

        val params = ArrayList<SampleParam>(nparams)
        for (i in 0 until nparams) {
            val idx = config + 2 + i * 3
            requireSize(d, idx + 3)
            val type = d[idx].toInt() and 0xFF
            val interval = d[idx + 1].toInt() and 0xFF
            val divisor = DIVISORS[(d[idx + 2].toInt() and 0x1C) ushr 2]
            val size = when (type) {
                PARAM_DEPTH, PARAM_PRESSURE -> 2
                PARAM_TEMPERATURE -> 1
                else -> throw ProtocolException("Vyper2: unknown sample type 0x${type.toString(16)}")
            }
            params += SampleParam(type, interval, divisor, size)
        }

        var profile = config + 2 + nparams * 3
        requireSize(d, profile + 5)
        // HelO2 dives may carry an extra 12 byte block before the profile; it is
        // absent exactly when the profile already starts with the 01 00 00 marker.
        if (!(d[profile].toInt() == 0x01 && d[profile + 1].toInt() == 0x00 && d[profile + 2].toInt() == 0x00)) {
            profile += 12
        }
        requireSize(d, profile + 5)

        val intervalSeconds = d[INTERVAL_SAMPLE_OFFSET].toInt() and 0xFF
        if (intervalSeconds == 0) throw ProtocolException("Vyper2: invalid sample interval")

        val samples = ArrayList<Sample>()
        val events = ArrayList<Event>()
        var marker = u16le(d, profile + 3)
        var time = 0
        var nsamples = 0
        var offset = profile + 5
        var activeGas = if (gas.oxygen.isNotEmpty()) gas.initialIndex else -1

        while (offset < d.size) {
            var depthMm: Int? = null
            var tempMk: Int? = null
            var tankMbar: Int? = null

            for (p in params) {
                if (p.interval == 0 || nsamples % p.interval != 0) continue
                if (offset + p.size > d.size) throw ProtocolException("Vyper2: profile truncated")
                when (p.type) {
                    PARAM_DEPTH -> depthMm = u16le(d, offset) * 1000 / p.divisor
                    PARAM_PRESSURE -> {
                        val v = u16le(d, offset)
                        if (v != 0xFFFF) tankMbar = v * 1000 / p.divisor
                    }
                    PARAM_TEMPERATURE -> tempMk = celsiusToMilliKelvin(d[offset].toInt(), p.divisor)
                }
                offset += p.size
            }

            if (nsamples + 1 == marker) {
                val group = readEvents(d, offset, marker, time, events, gas)
                offset = group.offset
                marker = group.marker
                if (group.gasIndex >= 0) activeGas = group.gasIndex
            }

            samples += Sample(
                timeOffsetSeconds = time,
                depthMm = depthMm,
                temperatureMk = tempMk,
                activeGasIndex = if (activeGas >= 0) activeGas else null,
                tankPressuresMbar = if (tankMbar != null) mapOf(0 to tankMbar) else emptyMap(),
            )

            time += intervalSeconds
            nsamples++
        }

        return Profile(samples, events)
    }

    private class EventGroup(val offset: Int, val marker: Int, val gasIndex: Int)

    /**
     * Read one event group starting at [start] (a marker tick), appending discrete
     * events to [events]. The group runs until a next-marker record (0x01), the end
     * of the buffer, or an unknown event. Deco, safety and deep stops surface as a
     * [EventType.DECO] event when the stop begins (TODO: this format carries no stop
     * depth or time, so those stay null).
     */
    private fun readEvents(
        d: ByteArray,
        start: Int,
        marker: Int,
        time: Int,
        events: MutableList<Event>,
        gas: GasInfo,
    ): EventGroup {
        var offset = start
        var currentMarker = marker
        var gasIndex = -1

        while (offset < d.size) {
            val event = d[offset++].toInt() and 0xFF
            when (event) {
                0x01 -> { // next event marker; terminates this group
                    if (offset + 4 > d.size) break
                    currentMarker += u16le(d, offset + 2)
                    offset += 4
                    return EventGroup(offset, currentMarker, gasIndex)
                }
                0x02 -> { // surfaced
                    if (offset + 2 > d.size) break
                    events += Event(time, EventType.SURFACE)
                    offset += 2
                }
                0x03 -> { // typed event; the high bit marks the end of a state
                    if (offset + 2 > d.size) break
                    val type = d[offset].toInt() and 0xFF
                    val beginning = type and 0x80 == 0
                    when (type and 0x7F) {
                        0x00, 0x01, 0x02, 0x03, 0x13, 0x14 -> if (beginning) events += Event(time, EventType.DECO)
                        0x04 -> events += Event(time, EventType.ASCENT_RATE)
                        0x05, 0x06, 0x07, 0x09, 0x0C, 0x0D, 0x0E, 0x0F, 0x10, 0x11, 0x12 ->
                            events += Event(time, EventType.WARNING)
                        0x0A -> events += Event(time, EventType.WARNING, value = 80)
                        0x0B -> events += Event(time, EventType.WARNING, value = 100)
                        else -> events += Event(time, EventType.OTHER)
                    }
                    offset += 2
                }
                0x04 -> { // bookmark or compass heading
                    if (offset + 4 > d.size) break
                    val heading = u16le(d, offset + 2)
                    if (heading == 0xFFFF) events += Event(time, EventType.BOOKMARK)
                    offset += 4
                }
                0x05 -> { // gas change, oxygen only
                    if (offset + 2 > d.size) break
                    val o2 = d[offset].toInt() and 0xFF
                    val idx = findGasMix(gas, o2, 0)
                    if (idx >= 0) {
                        gasIndex = idx
                        events += Event(time, EventType.GAS_SWITCH, value = GasSwitch.value(o2, gas.helium[idx]))
                    }
                    offset += 2
                }
                0x06 -> { // gas change, helium + oxygen
                    if (offset + 4 > d.size) break
                    val type = d[offset].toInt() and 0xFF
                    val he = d[offset + 1].toInt() and 0xFF
                    val o2 = d[offset + 2].toInt() and 0xFF
                    val idx = type and 0x0F
                    if (idx in gas.oxygen.indices && o2 == gas.oxygen[idx] && he == gas.helium[idx]) {
                        gasIndex = idx
                        events += Event(time, EventType.GAS_SWITCH, value = GasSwitch.value(o2, gas.helium[idx]))
                    }
                    offset += 4
                }
                else -> break // unknown event; stop this group
            }
        }
        return EventGroup(offset, currentMarker, gasIndex)
    }

    private fun findGasMix(gas: GasInfo, o2: Int, he: Int): Int {
        for (i in gas.oxygen.indices) {
            if (gas.oxygen[i] == o2 && gas.helium[i] == he) return i
        }
        return -1
    }

    private fun requireSize(d: ByteArray, min: Int) {
        if (d.size < min) throw ProtocolException("Vyper2 record too small: ${d.size} bytes, need $min")
    }

    private fun celsiusToMilliKelvin(signedByte: Int, divisor: Int): Int {
        val c = signedByte.toByte().toInt() // sign-extend
        return c * 1000 / divisor + ZERO_CELSIUS_MK
    }

    companion object {
        const val HELO2 = 0x15

        // HelO2 field offsets, relative to the start of the dive data.
        private const val MAXDEPTH_OFFSET = 0x09
        private const val DIVETIME_OFFSET = 0x0D
        private const val DATETIME_OFFSET = 0x17
        private const val INTERVAL_SAMPLE_OFFSET = 0x1E
        private const val GASMODE_OFFSET = 0x1F
        private const val INITIAL_GASMIX_OFFSET = 0x26
        private const val GASMIX_OFFSET = 0x54
        private const val GASMIX_COUNT = 8
        private const val GASMIX_STRIDE = 6

        private const val MODE_AIR = 0
        private const val MODE_GAUGE = 2
        private const val MODE_FREEDIVE = 3

        private const val MAX_PARAMS = 3
        private const val PARAM_DEPTH = 0x64
        private const val PARAM_PRESSURE = 0x68
        private const val PARAM_TEMPERATURE = 0x74
        private val DIVISORS = intArrayOf(1, 2, 4, 5, 10, 50, 100, 1000)

        /** Days from 1970-01-01 to the given civil date (proleptic Gregorian). */
        private fun civilToEpochSeconds(year: Int, month: Int, day: Int, hour: Int, minute: Int, second: Int): Long {
            val y = if (month <= 2) year - 1 else year
            val era = (if (y >= 0) y else y - 399) / 400
            val yoe = y - era * 400
            val doy = (153 * (if (month > 2) month - 3 else month + 9) + 2) / 5 + day - 1
            val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
            val days = era.toLong() * 146_097 + doe - 719_468
            return days * 86_400 + hour * 3_600L + minute * 60L + second
        }
    }
}
