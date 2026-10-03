package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.RawDive
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
                    Triple(100, 6, 21), // 10.0 m, 6 C, air
                    Triple(359, 5, 21), // 35.9 m, 5 C, air
                    Triple(0, 5, 50), // surfacing, switch to EAN50
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
        assertEquals(3, dive.samples.size)
        assertEquals(0, dive.samples[0].timeOffsetSeconds)
        assertEquals(20, dive.samples[2].timeOffsetSeconds)
        assertEquals(15_300, dive.meanDepthMm)

        // Initial gas at t=0, then a switch at t=20.
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
}
