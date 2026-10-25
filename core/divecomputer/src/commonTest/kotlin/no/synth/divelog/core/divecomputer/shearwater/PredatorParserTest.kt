package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.model.DecoAlgorithm
import no.synth.divelog.core.model.DecoModel
import no.synth.divelog.core.model.DiveMode
import no.synth.divelog.core.model.EventType
import kotlin.test.Test
import kotlin.test.assertEquals

class PredatorParserTest {
    private val block = 128

    /** Build a synthetic Predator record: opening block, samples, closing block. */
    private fun record(
        number: Int = 0,
        timestamp: Long,
        units: Int,
        o2: Int,
        samples: List<Triple<Int, Int, Int>>, // depth tenths, temp C, o2 %
        durationMinutes: Int,
    ): ByteArray {
        val opening = ByteArray(block)
        opening[0] = 0xFF.toByte(); opening[1] = 0xFF.toByte()
        opening[2] = ((number shr 8) and 0xFF).toByte()
        opening[3] = (number and 0xFF).toByte()
        opening[8] = units.toByte()
        for (i in 0 until 4) opening[12 + i] = ((timestamp shr (8 * (3 - i))) and 0xFF).toByte()
        opening[20] = o2.toByte()

        val sampleBytes = ByteArray(samples.size * 16)
        samples.forEachIndexed { i, (depth, temp, sO2) ->
            val o = i * 16
            sampleBytes[o] = ((depth shr 8) and 0xFF).toByte()
            sampleBytes[o + 1] = (depth and 0xFF).toByte()
            sampleBytes[o + 7] = sO2.toByte()
            sampleBytes[o + 13] = temp.toByte()
        }

        val closing = ByteArray(block)
        closing[0] = 0xFF.toByte(); closing[1] = 0xFE.toByte()
        closing[6] = ((durationMinutes shr 8) and 0xFF).toByte()
        closing[7] = (durationMinutes and 0xFF).toByte()

        return opening + sampleBytes + closing
    }

    @Test
    fun parsesSummarySamplesAndGasSwitches() {
        val raw = RawDive(
            fingerprint = "01020304",
            formatId = PredatorDump.FORMAT_ID,
            data = record(
                number = 871,
                timestamp = 0x01020304,
                units = 0, // metric
                o2 = 21,
                samples = listOf(
                    Triple(100, 6, 21), // t=0,  10.0 m, 6 C, air
                    Triple(359, 5, 21), // t=10, 35.9 m, 5 C, air (deepest)
                    Triple(60, 5, 50), // t=20,  6.0 m, switch to EAN50 (deco stop)
                    Triple(0, 5, 50), // t=30,  surfaced - trimmed off
                ),
                durationMinutes = 2,
            ),
        )

        val dive = PredatorParser().parse(raw)

        assertEquals(871, dive.number)
        assertEquals(0x01020304L, dive.startEpochSeconds)
        assertEquals(35_900, dive.maxDepthMm)
        assertEquals(278_150, dive.waterTempMk) // min temp 5 C
        assertEquals(120, dive.durationSeconds) // from closing block, minutes -> seconds
        assertEquals(3, dive.samples.size) // the trailing surfaced sample is trimmed
        assertEquals(0, dive.samples[0].timeOffsetSeconds)
        assertEquals(20, dive.samples[2].timeOffsetSeconds)
        assertEquals(17_300, dive.meanDepthMm) // (10.0 + 35.9 + 6.0)/3

        // Initial gas at t=0, then a switch at t=20 (both within the trimmed dive).
        assertEquals(2, dive.events.size)
        assertEquals(EventType.GAS_SWITCH, dive.events[0].type)
        assertEquals(0, dive.events[0].timeOffsetSeconds)
        assertEquals(20, dive.events[1].timeOffsetSeconds)
    }

    @Test
    fun imperialDepthsConvertToMillimetres() {
        val raw = RawDive(
            fingerprint = "00",
            formatId = PredatorDump.FORMAT_ID,
            data = record(
                timestamp = 0,
                units = 1, // imperial
                o2 = 21,
                samples = listOf(Triple(1000, 50, 21)), // 100.0 ft
                durationMinutes = 1,
            ),
        )
        val dive = PredatorParser().parse(raw)
        // 100 ft = 30480 mm
        assertEquals(30_480, dive.maxDepthMm)
    }

    /** Two metric samples at 20 m with the given sample bytes patched in at [patch]. */
    private fun twoSamples(patch: (sample: Int, set: (offset: Int, value: Int) -> Unit) -> Unit): RawDive {
        val data = record(timestamp = 0, units = 0, o2 = 21, samples = listOf(Triple(200, 10, 21), Triple(200, 10, 21)), durationMinutes = 1)
        for (sample in 0 until 2) patch(sample) { offset, value -> data[block + sample * 16 + offset] = value.toByte() }
        return RawDive(fingerprint = "a", formatId = PredatorDump.FORMAT_ID, data = data)
    }

    @Test
    fun theDecoByteIsTheNdlWithoutAStopAndTheStopTimeWithOne() {
        val dive = PredatorParser().parse(
            twoSamples { sample, set ->
                set(9, 12) // deco time: 12 min
                set(5, 7) // time to surface, not a stop time
                if (sample == 1) set(3, 6) // stop depth: 6 m (whole metres)
            },
        )
        val (noStop, stop) = dive.samples
        assertEquals(12 * 60, noStop.ndlSeconds)
        // Zero, not null, for what does not apply: a file format carries a null over.
        assertEquals(0, noStop.stopTimeSeconds)
        assertEquals(0, noStop.stopDepthMm)
        assertEquals(0, stop.ndlSeconds)
        assertEquals(12 * 60, stop.stopTimeSeconds)
        assertEquals(6_000, stop.stopDepthMm)
    }

    @Test
    fun anImperialStopDepthIsWholeFeetAndAStopWithoutATimeHasNone() {
        val data = record(timestamp = 0, units = 1, o2 = 21, samples = listOf(Triple(660, 50, 21), Triple(660, 50, 21)), durationMinutes = 1)
        data[block + 3] = 20 // first sample: a 20 ft stop, deco byte 0
        data[block + 16 + 3] = 20 // second: the same stop, 3 minutes
        data[block + 16 + 9] = 3
        val (noTime, timed) = PredatorParser().parse(RawDive(fingerprint = "a", formatId = PredatorDump.FORMAT_ID, data = data)).samples
        assertEquals(6_096, noTime.stopDepthMm) // 20 ft, not 2.0 ft
        assertEquals(0, noTime.stopTimeSeconds)
        assertEquals(0, noTime.ndlSeconds)
        assertEquals(3 * 60, timed.stopTimeSeconds)
    }

    @Test
    fun ppO2IsTheByteAtSixOnARebreatherAndAbsentOnOpenCircuit() {
        fun ppO2(status: Int) = PredatorParser().parse(
            twoSamples { _, set ->
                set(11, status)
                set(6, 130) // average ppO2, 1.30 bar
                set(12, 54) // raw sensor byte: needs calibration, never ppO2 as is
            },
        ).samples.map { it.ppO2Mbar }
        assertEquals(listOf(null, null), ppO2(status = 0x10)) // open circuit
        assertEquals(listOf(1_300, 1_300), ppO2(status = 0x00)) // rebreather, internal sensors
        assertEquals(listOf(1_300, 1_300), ppO2(status = 0x02)) // rebreather, external ppO2: the setpoint
    }

    @Test
    fun aBailoutOnARebreatherDiveKeepsTheOpenCircuitPpO2() {
        val dive = PredatorParser().parse(
            twoSamples { sample, set ->
                set(11, if (sample == 0) 0x00 else 0x10) // on the loop, then open circuit
                set(6, if (sample == 0) 130 else 45)
            },
        )
        assertEquals(DiveMode.CLOSED_CIRCUIT, dive.diveMode)
        assertEquals(listOf(1_300, 450), dive.samples.map { it.ppO2Mbar })
    }

    @Test
    fun aBlockLogsHeaderGivesItsDiveModeAndDecoModel() {
        // Petrel 1 / Predator blocks: GF at +4/+5, deco model at +67, dive mode at +112 from
        // log version 8 (+127). Samples are open circuit, so only the header says SC.
        fun blockLog(version: Int) = record(timestamp = 0, units = 0, o2 = 21, samples = listOf(Triple(100, 10, 21)), durationMinutes = 1).also {
            it[4] = 30
            it[5] = 70
            it[67] = 0
            it[112] = 4
            it[127] = version.toByte()
            it[block + 11] = 0x10
        }
        val v8 = PredatorParser().parse(RawDive("a", blockLog(8), PredatorDump.FORMAT_ID))
        assertEquals(DiveMode.SEMI_CLOSED, v8.diveMode)
        assertEquals(DecoModel(DecoAlgorithm.BUHLMANN, 30, 70), v8.decoModel)
        // Before version 8 the header byte is not the mode; the open-circuit samples decide.
        assertEquals(DiveMode.OPEN_CIRCUIT, PredatorParser().parse(RawDive("a", blockLog(7), PredatorDump.FORMAT_ID)).diveMode)
    }
}
