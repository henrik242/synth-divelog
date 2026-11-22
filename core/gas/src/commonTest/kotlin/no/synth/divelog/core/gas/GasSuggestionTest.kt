package no.synth.divelog.core.gas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GasSuggestionTest {
    private val ean50 = BreathingGas(500)
    private val trimix2135 = BreathingGas(210, 350)

    @Test
    fun shallowNitroxWithOxygen() {
        assertEquals(GasSuggestion(ean50, listOf(BreathingGas.OXYGEN)), suggestGases(10000))
    }

    @Test
    fun ean32AtThirtyMetres() {
        // EAN32 is 1.29 bar at 30 m and its END is the 30 m limit.
        assertEquals(GasSuggestion(BreathingGas(320), listOf(ean50, BreathingGas.OXYGEN)), suggestGases(30000))
    }

    @Test
    fun normoxicTrimixPastTheEndLimit() {
        assertEquals(GasSuggestion(trimix2135, listOf(ean50, BreathingGas.OXYGEN)), suggestGases(45000))
    }

    @Test
    fun withoutAnEndLimitAirBeatsHelium() {
        assertEquals(GasSuggestion(BreathingGas.AIR, listOf(ean50, BreathingGas.OXYGEN)), suggestGases(45000, maxEndMm = null))
    }

    @Test
    fun hypoxicBottomGasAddsTrimixDecoGas() {
        // 18/45 is 1.46 bar at 70 m, over the 1.4 limit.
        assertEquals(
            GasSuggestion(BreathingGas(150, 550), listOf(trimix2135, ean50, BreathingGas.OXYGEN)),
            suggestGases(70000),
        )
    }

    @Test
    fun bestMixPastTheStandardGases() {
        // 10/70 is 1.62 bar at 150 m: 8 % O2 and 75 % He keep pO2 and END within the limits.
        assertEquals(
            GasSuggestion(BreathingGas(80, 750), listOf(trimix2135, ean50, BreathingGas.OXYGEN)),
            suggestGases(150000),
        )
    }

    @Test
    fun lowerPpO2PicksLeanerGas() {
        assertEquals(BreathingGas(180, 450), suggestGases(45000, bottomPpO2Mbar = 1100)?.bottom)
    }

    @Test
    fun noDecoGasDeeperThanTheDive() {
        assertEquals(GasSuggestion(ean50, emptyList()), suggestGases(5000))
    }

    @Test
    fun noMixFitsImpossibleLimits() {
        // At 300 m a 1.6 bar pO2 needs 5 % O2 and a 0 m END 97 % He.
        assertNull(suggestGases(300000, bottomPpO2Mbar = 1600, maxEndMm = 0))
    }
}
