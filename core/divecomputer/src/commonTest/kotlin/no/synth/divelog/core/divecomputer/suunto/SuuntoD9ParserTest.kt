package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.model.EventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/** Decodes the crafted HelO2 record ([SyntheticD9]) and checks every parsed field. */
class SuuntoD9ParserTest {
    private fun rawCanonical(): RawDive {
        val data = SyntheticD9.helo2Record(maxDepthCm = 600)
        return RawDive(fingerprint = "test", data = data, formatId = SuuntoD9Dump.FORMAT_ID)
    }

    @Test
    fun decodesSummaryFields() {
        val dive = SuuntoD9Parser().parse(rawCanonical())
        // 2023-07-15 08:30:45 UTC.
        assertEquals(1689409845L, dive.startEpochSeconds)
        assertEquals(0, dive.utcOffsetSeconds)
        assertEquals(SyntheticD9.DIVETIME_MIN * 60, dive.durationSeconds)
        assertEquals(6000, dive.maxDepthMm)
        // Mean of 1000, 3000, 6000, 4000, 500 mm.
        assertEquals(2900, dive.meanDepthMm)
        // Coldest sample temperature: 18 C.
        assertEquals(18 * 1000 + 273_150, dive.waterTempMk)
        assertNull(dive.airTempMk)
    }

    @Test
    fun decodesDepthAndTemperatureProfile() {
        val dive = SuuntoD9Parser().parse(rawCanonical())
        assertEquals(5, dive.samples.size)
        assertEquals(listOf(1000, 3000, 6000, 4000, 500), dive.samples.map { it.depthMm })
        assertEquals(listOf(0, 10, 20, 30, 40), dive.samples.map { it.timeOffsetSeconds })
        // Temperature is recorded on even ticks only.
        assertEquals(20 * 1000 + 273_150, dive.samples[0].temperatureMk)
        assertNull(dive.samples[1].temperatureMk)
        assertEquals(18 * 1000 + 273_150, dive.samples[2].temperatureMk)
    }

    @Test
    fun tracksGasSwitchEventAndActiveMix() {
        val dive = SuuntoD9Parser().parse(rawCanonical())
        val switches = dive.events.filter { it.type == EventType.GAS_SWITCH }
        assertEquals(1, switches.size)
        assertEquals(SyntheticD9.GAS1_O2.toLong(), switches[0].value)
        assertEquals(SyntheticD9.GAS_SWITCH_TICK * SyntheticD9.INTERVAL, switches[0].timeOffsetSeconds)
        // Active mix flips from 0 to 1 at the switch tick.
        assertEquals(0, dive.samples[0].activeGasIndex)
        assertEquals(0, dive.samples[1].activeGasIndex)
        assertEquals(1, dive.samples[2].activeGasIndex)
        assertEquals(1, dive.samples[4].activeGasIndex)
    }

    @Test
    fun rejectsUnwiredModels() {
        assertFailsWith<ProtocolException> {
            SuuntoD9Parser(model = 0x1C).parse(rawCanonical()) // DX layout not implemented
        }
    }
}
