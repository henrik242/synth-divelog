package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.DiveLogParser
import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.u16be
import no.synth.divelog.core.divecomputer.u24be
import no.synth.divelog.core.divecomputer.u32be
import no.synth.divelog.core.model.DecoAlgorithm
import no.synth.divelog.core.model.DecoModel
import no.synth.divelog.core.model.DiveMode
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.GasSwitch
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.Sample
import no.synth.divelog.core.model.units.ATM_BAR
import no.synth.divelog.core.model.units.BAR_PER_PSI
import no.synth.divelog.core.model.units.MM_PER_FOOT
import no.synth.divelog.core.model.units.ZERO_CELSIUS_MK
import no.synth.divelog.core.model.units.feetToMm
import kotlin.math.roundToInt

/**
 * Parses the older Predator log format shared by the Predator and Petrel 1, and the
 * record form Shearwater Cloud stores dives in.
 *
 * Block layout: a 128-byte opening block, a run of fixed-size sample records, then a
 * 128-byte closing block. The Predator stores 16-byte samples; the Petrel 1 stores
 * 32-byte records whose first 16 bytes hold the same fields, plus CNS and (from log
 * version 7) air-integration pressures. Depths are tenths of a metre or foot per the
 * opening block's units flag; temperature is a signed whole degree, Fahrenheit on an
 * imperial dive and Celsius on a metric one. Field
 * offsets were confirmed against real captures; the CNS, pressure and freedive fields
 * follow libdivecomputer's `shearwater_predator_parser.c`.
 *
 * Record layout ([PNF_FORMAT_ID]): a stream of 32-byte records, each led by a type
 * byte. Opening (0x10) and closing (0x20) records are the first 32 bytes of those
 * blocks with byte 0 replaced by the type; a sample record (0x01) is the type byte
 * followed by the sample fields, so they sit one byte later. The closing record holds
 * the duration in seconds. Confirmed against 302 Shearwater Cloud dives. From log
 * version 9 the opening records also carry the sample interval, which replaces
 * [sampleIntervalSeconds]. Extended records (0xE1) add two more transmitters, and a
 * freedive log has packed freedive records (0x02) instead of samples. A Peregrine
 * downloads its dives in this form too.
 */
class PredatorParser(
    private val sampleIntervalSeconds: Int = DEFAULT_SAMPLE_INTERVAL_SECONDS,
) : DiveLogParser {
    override val formatId: String = PredatorDump.FORMAT_ID

    override fun parse(raw: RawDive): IncomingDive {
        val d = raw.data
        val layout = layoutOf(d, raw.formatId)
        val header = layout.header
        val imperial = header[UNITS_OFFSET].toInt() == 1
        val number = u16be(header, NUMBER_OFFSET)
        val startEpoch = u32be(header, START_TIME_OFFSET)
        val durationSeconds = layout.durationSeconds
        val intervalMs = layout.intervalMs ?: (sampleIntervalSeconds * 1_000)
        // The whole second each sample falls in, so an interval of no whole seconds keeps its real time.
        fun timeOf(index: Int) = (index.toLong() * intervalMs / 1_000).toInt()

        val samples = ArrayList<Sample>()
        val events = ArrayList<Event>()
        var previousGas: Pair<Int, Int>? = null
        // Tank pressures by the log's own tank id, renumbered densely once all are known.
        val pressures = ArrayList<Map<Int, Int>>()
        // An older log has no dive mode in its header; the sample status flags tell it.
        var sampleMode: DiveMode? = null

        for ((index, offset) in layout.sampleOffsets.withIndex()) {
            val time = timeOf(index)
            val depthMm = toMillimetres(u16be(d, offset + S_DEPTH), imperial)
            // The sample temperature follows the dive's unit flag: Fahrenheit for
            // an imperial dive, Celsius for a metric one.
            val temperature = sampleTemperature(d[offset + S_TEMP])
            val tempMk = if (imperial) fahrenheitToMilliKelvin(temperature) else celsiusToMilliKelvin(temperature)
            // One byte holds the stop time when a stop depth (whole metres or feet) is
            // set, else the NDL. The word at +4 is time to surface, not the stop time.
            val stopDepth = u16be(d, offset + S_STOP_DEPTH)
            val decoMinutes = d[offset + S_DECO_TIME].toInt() and 0xFF
            // Zeros, not nulls, for "no stop" and "no NDL left", as libdivecomputer reports
            // them: the file formats leave a null out and carry the previous value over it.
            val decoSeconds = decoMinutes * 60
            val stopDepthMm = if (stopDepth == 0) 0 else stopDepthToMillimetres(stopDepth, imperial)
            val stopTimeSeconds = if (stopDepth == 0) 0 else decoSeconds
            val ndlSeconds = if (stopDepth == 0) decoSeconds else 0
            val o2 = d[offset + S_O2].toInt() and 0xFF
            val he = d[offset + S_HE].toInt() and 0xFF
            val ppO2Mbar = loopPpO2Mbar(d, offset)
            val status = d[offset + S_STATUS].toInt() and 0xFF
            if (status and STATUS_OC == 0) {
                sampleMode = if (status and STATUS_SC != 0) DiveMode.SEMI_CLOSED else DiveMode.CLOSED_CIRCUIT
            }

            samples += Sample(
                timeOffsetSeconds = time,
                depthMm = depthMm,
                temperatureMk = tempMk,
                ppO2Mbar = ppO2Mbar,
                ndlSeconds = ndlSeconds,
                stopDepthMm = stopDepthMm,
                stopTimeSeconds = stopTimeSeconds,
                ttsSeconds = u16be(d, offset + S_TTS) * 60,
                cnsPermille = if (layout.wide) (d[offset + S_CNS].toInt() and 0xFF) * 10 else null,
            )
            pressures += tankPressures(d, offset, layout.extended[index], layout)

            val gas = o2 to he
            if (o2 > 0 && gas != previousGas) {
                events += Event(timeOffsetSeconds = time, type = EventType.GAS_SWITCH, value = GasSwitch.value(o2, he))
                previousGas = gas
            }
        }
        withDenseTankIndexes(samples, pressures)
        layout.freediveOffsets.forEachIndexed { index, offset -> samples += freediveSample(d, offset, timeOf(index), layout) }
        val diveMode = layout.diveMode
            ?: if (layout.freediveOffsets.isNotEmpty()) DiveMode.FREEDIVE else sampleMode ?: DiveMode.OPEN_CIRCUIT
        // An open-circuit dive has no loop: its ppO2 is only the gas fraction times depth.
        if (diveMode != DiveMode.CLOSED_CIRCUIT && diveMode != DiveMode.SEMI_CLOSED) {
            for (i in samples.indices) samples[i] = samples[i].copy(ppO2Mbar = null)
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
            durationSeconds = if (durationSeconds > 0) durationSeconds else timeOf(samples.size),
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
            diveMode = diveMode,
            decoModel = layout.decoModel,
        )
    }

    /**
     * Where a layout keeps the opening fields, its samples (each at the sample-field base)
     * and duration, and the sample interval (milliseconds) when the log records one. [wide] samples (32
     * bytes: Petrel 1 blocks and records) also hold CNS and air-integration pressures;
     * [extended] maps a sample's index to the fields of the extended record (0xE1) after
     * it. A freedive log has [freediveOffsets] (8-byte samples) instead of samples.
     */
    private class Layout(
        val header: ByteArray,
        val sampleOffsets: List<Int>,
        val durationSeconds: Int,
        val wide: Boolean,
        val logVersion: Int,
        val intervalMs: Int? = null,
        val aiMode: Int = 0,
        val extended: Map<Int, Int> = emptyMap(),
        val freediveOffsets: List<Int> = emptyList(),
        val surfaceMbar: Int = DEFAULT_SURFACE_MBAR,
        val waterDensity: Int = DEFAULT_WATER_DENSITY,
        /** From the header from log version 8 on; else null, and the samples tell it. */
        val diveMode: DiveMode? = null,
        /** From the header in every log version; null for an unknown model byte. */
        val decoModel: DecoModel? = null,
    )

    /**
     * The layout of [d]. A Petrel-family log is told apart by its data, as libdivecomputer
     * does: blocks start with 0xFFFF, records with a record type. The format id only
     * separates those from the Predator's 16-byte samples.
     */
    private fun layoutOf(d: ByteArray, formatId: String): Layout {
        val petrelFamily = formatId == PETREL_FORMAT_ID || formatId == PNF_FORMAT_ID
        val records = petrelFamily && d.size >= 2 && u16be(d, 0) != 0xFFFF
        return if (records) recordLayout(d) else blockLayout(d, wide = petrelFamily)
    }

    private fun blockLayout(d: ByteArray, wide: Boolean): Layout {
        if (d.size < 2 * BLOCK) throw ProtocolException("Predator record too small: ${d.size} bytes")
        val stride = if (wide) PETREL_SAMPLE_STRIDE else SAMPLE_SIZE
        // The closing block is the first 128-aligned block (after the opening one)
        // that starts with the close marker. A ring-extracted Predator dive ends
        // exactly at its closing block, but a Petrel's per-dive blob carries one or
        // more trailing blocks after it, so assuming the last block is the closer
        // reads the close marker as a 0xFFFE depth sample and padding as duration.
        val closing = closingBlockOffset(d)
        val offsets = generateSequence(BLOCK) { it + stride }.takeWhile { it + SAMPLE_SIZE <= closing }.toList()
        val version = d[BLOCK_LOG_VERSION].toInt() and 0xFF
        return Layout(
            d,
            offsets,
            u16be(d, closing + CLOSE_DURATION_OFFSET) * 60,
            wide = wide,
            logVersion = version,
            diveMode = if (version >= MIN_MODE_LOG_VERSION) diveMode(d[BLOCK_DIVE_MODE]) else null,
            decoModel = decoModel(d, BLOCK_DECO_MODEL, BLOCK_GF),
        )
    }

    private fun recordLayout(d: ByteArray): Layout {
        val records = (0 until d.size / RECORD).map { it * RECORD }
        fun first(type: Byte) = records.firstOrNull { d[it] == type }
        val opening = first(REC_OPENING) ?: throw ProtocolException("Shearwater record log without an opening record")
        val duration = first(REC_CLOSING)?.let { u24be(d, it + REC_CLOSE_DURATION_SECONDS) } ?: 0
        val opening4 = first(REC_OPENING_4)
        val version = opening4?.let { d[it + REC_LOG_VERSION].toInt() and 0xFF } ?: 0
        val samples = ArrayList<Int>()
        val extended = HashMap<Int, Int>()
        val freedive = ArrayList<Int>()
        for (r in records) {
            when (d[r]) {
                REC_SAMPLE -> samples += r + 1
                REC_SAMPLE_EXT -> if (samples.isNotEmpty()) extended[samples.lastIndex] = r + 1
                // Four 8-byte samples packed in one record; a zero sample pads the last one.
                REC_FREEDIVE -> (0 until 4).map { r + it * FREEDIVE_SAMPLE }
                    .takeWhile { o -> (o until o + FREEDIVE_SAMPLE).any { d[it] != 0.toByte() } }
                    .let { freedive += it }
            }
        }
        return Layout(
            d.copyOfRange(opening, opening + RECORD),
            samples,
            duration,
            wide = true,
            logVersion = version,
            intervalMs = recordIntervalMs(d, version, first(REC_OPENING_5)),
            aiMode = if (version >= MIN_AI_LOG_VERSION) opening4?.let { d[it + REC_AI_MODE].toInt() and 0xFF } ?: 0 else 0,
            extended = extended,
            freediveOffsets = freedive,
            surfaceMbar = first(REC_OPENING_1)?.let { u16be(d, it + REC_SURFACE_MBAR) }?.takeIf { it > 0 } ?: DEFAULT_SURFACE_MBAR,
            waterDensity = first(REC_OPENING_3)?.let { u16be(d, it + REC_DENSITY) }?.takeIf { it > 0 } ?: DEFAULT_WATER_DENSITY,
            diveMode = if (version >= MIN_MODE_LOG_VERSION) opening4?.let { diveMode(d[it + REC_DIVE_MODE]) } else null,
            decoModel = first(REC_OPENING_2)?.let { decoModel(d, it + REC_DECO_MODEL, opening + REC_GF) },
        )
    }

    /**
     * The sample interval in milliseconds from opening record 5 ([opening5]), which holds
     * it from log version 9 on. Null for an older log, so the default applies. Follows
     * libdivecomputer's `shearwater_predator_parser.c`.
     */
    private fun recordIntervalMs(d: ByteArray, version: Int, opening5: Int?): Int? {
        if (version < MIN_INTERVAL_LOG_VERSION || opening5 == null) return null
        return u16be(d, opening5 + REC_INTERVAL_MS).takeIf { it > 0 }
    }

    /**
     * The deco model byte at [modelAt] (0 GF, 1 VPM-B, 2 VPM-B/GFS, 3 DCIEM) with the
     * conservatism after it, and gradient factors low and high at [gfAt], as
     * libdivecomputer reads them. Null for an unknown model.
     */
    private fun decoModel(d: ByteArray, modelAt: Int, gfAt: Int): DecoModel? {
        fun percent(at: Int) = (d[at].toInt() and 0xFF).takeIf { it in DecoModel.GF_PERCENT_RANGE }
        return when (d[modelAt].toInt() and 0xFF) {
            DECO_GF -> DecoModel(DecoAlgorithm.BUHLMANN, gfLowPercent = percent(gfAt), gfHighPercent = percent(gfAt + 1))
            DECO_VPMB, DECO_VPMB_GFS -> DecoModel(DecoAlgorithm.VPM, conservatism = d[modelAt + 1].toInt() and 0xFF)
            DECO_DCIEM -> DecoModel(DecoAlgorithm.DCIEM)
            else -> null
        }
    }

    /**
     * Air-integration pressures of a wide sample from log version 7 (transmitters 1 and 2,
     * or the HP CCR pair), plus those of its extended record (transmitters 3 and 4 from
     * version 13, HP CCR from 14), keyed by the log's tank id 0..5. Readings are 2 psi
     * steps under a battery nibble; values from 0xFFF0 mean off or no signal.
     */
    private fun tankPressures(d: ByteArray, offset: Int, extended: Int?, layout: Layout): Map<Int, Int> {
        if (!layout.wide || layout.logVersion < MIN_AI_LOG_VERSION) return emptyMap()
        val out = HashMap<Int, Int>()
        val pairBase = if (layout.aiMode == AI_HPCCR) 4 else 0
        for ((i, field) in listOf(S_TANK_1, S_TANK_2).withIndex()) {
            transmitterMbar(u16be(d, offset + field))?.let { out[pairBase + i] = it }
        }
        if (extended != null) {
            if (layout.logVersion >= MIN_EXT_TANKS_LOG_VERSION) {
                for (i in 0 until 2) {
                    transmitterMbar(u16be(d, extended + i * 2))?.let { out[2 + i] = it }
                }
            }
            if (layout.logVersion >= MIN_EXT_HPCCR_LOG_VERSION) {
                for (i in 0 until 2) {
                    val raw = u16be(d, extended + 4 + i * 2)
                    if (raw != 0) out[4 + i] = twoPsiToMbar(raw)
                }
            }
        }
        return out
    }

    /**
     * A transmitter field in mbar, or null for no reading: the special codes from 0xFFF0
     * (off, no signal, not paired) and a zero reading, which an unused slot holds (we do
     * not track which transmitters are enabled, as libdivecomputer does).
     */
    private fun transmitterMbar(raw: Int): Int? {
        val steps = raw and 0x0FFF
        return if (raw >= AI_SPECIAL || steps == 0) null else twoPsiToMbar(steps)
    }

    /** Fill in [pressures] with tank ids renumbered 0, 1, ... in id order, so tank 0 is the first transmitter in use. */
    private fun withDenseTankIndexes(samples: MutableList<Sample>, pressures: List<Map<Int, Int>>) {
        val index = pressures.flatMap { it.keys }.distinct().sorted().withIndex().associate { (i, id) -> id to i }
        for (i in samples.indices) {
            val p = pressures.getOrNull(i) ?: continue
            if (p.isNotEmpty()) samples[i] = samples[i].copy(tankPressuresMbar = p.mapKeys { (id, _) -> index.getValue(id) })
        }
    }

    /** One freedive sample: depth from absolute pressure (mbar at +1), temperature in tenths of a degree C (+3). */
    private fun freediveSample(d: ByteArray, offset: Int, time: Int, layout: Layout): Sample {
        val gaugeMbar = u16be(d, offset + 1) - layout.surfaceMbar
        val depthMm = (gaugeMbar * PA_PER_MBAR * 1_000 / (layout.waterDensity * GRAVITY)).roundToInt().coerceAtLeast(0)
        val tenths = u16be(d, offset + 3).toShort().toInt()
        return Sample(timeOffsetSeconds = time, depthMm = depthMm, temperatureMk = tenths * 100 + ZERO_CELSIUS_MK)
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
        if (imperial) (tenths * MM_PER_FOOT / 10).roundToInt() else tenths * MM_PER_TENTH_METRE

    private fun stopDepthToMillimetres(whole: Int, imperial: Boolean): Int =
        if (imperial) feetToMm(whole) else whole * 1_000

    /**
     * The ppO2 the computer used (hundredths of a bar). On the loop it is the internal
     * sensors' average (libdivecomputer's SENSOR_AVERAGE); without them (external ppO2
     * flag, as on a fixed-setpoint Petrel with no sensors calibrated) it is the setpoint,
     * which libdivecomputer leaves out. On open circuit it is the gas fraction times depth:
     * kept for a bailout on a rebreather dive, so the loop's last ppO2 does not carry over
     * it after an export, and dropped on an open-circuit dive. The raw per-sensor bytes
     * need calibration, so never them.
     */
    private fun loopPpO2Mbar(d: ByteArray, sampleOffset: Int): Int? {
        val hundredths = d[sampleOffset + S_PPO2_AVERAGE].toInt() and 0xFF
        return if (hundredths == 0) null else hundredths * 10
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
        private const val BLOCK_LOG_VERSION = 127
        private const val BLOCK_GF = 4
        private const val BLOCK_DECO_MODEL = 67
        private const val BLOCK_DIVE_MODE = 112
        private const val UNITS_OFFSET = 8
        private const val START_TIME_OFFSET = 12

        // Sample fields
        private const val S_DEPTH = 0
        private const val S_STOP_DEPTH = 2
        private const val S_TTS = 4
        private const val S_PPO2_AVERAGE = 6
        private const val S_O2 = 7
        private const val S_HE = 8
        private const val S_DECO_TIME = 9
        private const val S_STATUS = 11
        private const val S_TEMP = 13
        private const val S_TANK_2 = 19
        private const val S_CNS = 22
        private const val S_TANK_1 = 27

        // Typed records, and the fields of each (offsets from the record's start)
        private const val RECORD = 32
        private const val REC_SAMPLE = 0x01.toByte()
        private const val REC_FREEDIVE = 0x02.toByte()
        private const val FREEDIVE_SAMPLE = 8
        private const val REC_SAMPLE_EXT = 0xE1.toByte()
        private const val REC_OPENING = 0x10.toByte()
        private const val REC_GF = 4
        private const val REC_OPENING_1 = 0x11.toByte()
        private const val REC_SURFACE_MBAR = 16
        private const val REC_OPENING_2 = 0x12.toByte()
        private const val REC_DECO_MODEL = 18
        private const val REC_OPENING_3 = 0x13.toByte()
        private const val REC_DENSITY = 3
        private const val REC_OPENING_4 = 0x14.toByte()
        private const val REC_DIVE_MODE = 1
        private const val REC_LOG_VERSION = 16
        private const val REC_AI_MODE = 28
        private const val REC_OPENING_5 = 0x15.toByte()
        private const val REC_INTERVAL_MS = 23
        private const val REC_CLOSING = 0x20.toByte()
        private const val REC_CLOSE_DURATION_SECONDS = 6

        // Log versions that add a field
        private const val MIN_MODE_LOG_VERSION = 8
        private const val MIN_INTERVAL_LOG_VERSION = 9

        // Deco models as the logs number them
        private const val DECO_GF = 0
        private const val DECO_VPMB = 1
        private const val DECO_VPMB_GFS = 2
        private const val DECO_DCIEM = 3

        // Air integration
        private const val MIN_AI_LOG_VERSION = 7
        private const val MIN_EXT_TANKS_LOG_VERSION = 13
        private const val MIN_EXT_HPCCR_LOG_VERSION = 14
        private const val AI_HPCCR = 4
        private const val AI_SPECIAL = 0xFFF0

        private val DEFAULT_SURFACE_MBAR = (ATM_BAR * 1_000).roundToInt()
        private const val DEFAULT_WATER_DENSITY = 1025
        private const val PA_PER_MBAR = 100.0
        private const val GRAVITY = 9.80665

        // Closing block (relative to its start)
        private const val CLOSE_DURATION_OFFSET = 6
        private const val CLOSE_MARKER_HI = 0xFF.toByte()
        private const val CLOSE_MARKER_LO = 0xFE.toByte()

        private const val MM_PER_TENTH_METRE = 100

        // Sample status flags
        private const val STATUS_SC = 0x08
        private const val STATUS_OC = 0x10

        /** A header dive-mode byte: CC, OC tec, gauge, ppO2 display, SC, CC2, OC rec, freedive. */
        private fun diveMode(b: Byte): DiveMode? = when (b.toInt() and 0xFF) {
            0, 5 -> DiveMode.CLOSED_CIRCUIT
            1, 6 -> DiveMode.OPEN_CIRCUIT
            2, 3 -> DiveMode.GAUGE
            4 -> DiveMode.SEMI_CLOSED
            7 -> DiveMode.FREEDIVE
            else -> null
        }

        /**
         * A sample's whole-degree temperature byte. A negative raw value is a reading just
         * below zero offset by 102 (libdivecomputer's fix), capped at zero.
         */
        private fun sampleTemperature(b: Byte): Int {
            val t = b.toInt()
            return if (t < 0) minOf(t + 102, 0) else t
        }

        private fun twoPsiToMbar(steps: Int): Int = (steps * 2 * BAR_PER_PSI * 1_000).roundToInt()

        private fun celsiusToMilliKelvin(celsius: Int): Int = celsius * 1_000 + ZERO_CELSIUS_MK

        private fun fahrenheitToMilliKelvin(fahrenheit: Int): Int =
            (fahrenheit - 32) * 5_000 / 9 + ZERO_CELSIUS_MK

    }
}
