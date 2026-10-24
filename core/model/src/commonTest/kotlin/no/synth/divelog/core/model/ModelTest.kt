package no.synth.divelog.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ModelTest {
    @Test
    fun diveEndIsStartPlusDuration() {
        val dive = Dive(startEpochSeconds = 1_000, utcOffsetSeconds = 3_600, durationSeconds = 2_400)
        assertEquals(3_400, dive.endEpochSeconds)
    }

    @Test
    fun gasNitrogenIsRemainder() {
        val ean32 = GasMix(o2Permille = 320, hePermille = 0)
        assertEquals(680, ean32.n2Permille)

        val trimix = GasMix(o2Permille = 180, hePermille = 450)
        assertEquals(370, trimix.n2Permille)
    }

    @Test
    fun unknownEventTypeFallsBackToOther() {
        assertEquals(EventType.GAS_SWITCH, EventType.fromStored("GAS_SWITCH"))
        assertEquals(EventType.OTHER, EventType.fromStored("something-new"))
    }

    @Test
    fun decoModelLabels() {
        assertEquals("Buhlmann GF 30/70", DecoModel(DecoAlgorithm.BUHLMANN, 30, 70).label)
        assertEquals("Buhlmann", DecoModel(DecoAlgorithm.BUHLMANN).label)
        assertEquals("VPM +2", DecoModel(DecoAlgorithm.VPM, conservatism = 2).label)
        assertEquals("VPM", DecoModel(DecoAlgorithm.VPM).label)
        assertEquals("DCIEM", DecoModel(DecoAlgorithm.DCIEM).label)
    }
}
