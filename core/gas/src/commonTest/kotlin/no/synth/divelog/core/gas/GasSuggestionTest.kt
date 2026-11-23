package no.synth.divelog.core.gas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class GasSuggestionTest {
    private val ean32 = BreathingGas(320)
    private val ean50 = BreathingGas(500)
    private val trimix2135 = BreathingGas(210, 350)

    @Test
    fun shallowBottomGasIsNoRicherThanEan32() {
        // EAN50 and oxygen fit at 10 m, but they are deco gases.
        assertEquals(GasSuggestion(ean32, listOf(BreathingGas.OXYGEN)), suggestGases(10000))
    }

    @Test
    fun bottomGasPpO2IsCheckedUnrounded() {
        // EAN32 at 4376 mbar is 1.4003 bar: whole mbar would round it to the limit, the planner warns.
        val depth = WaterColumn().depthMm(4376)
        assertEquals(BreathingGas.AIR, suggestGases(depth, maxEndMm = null)?.bottom)
    }

    @Test
    fun freshWaterAllowsARicherMixAtTheSameDepth() {
        assertEquals(BreathingGas.AIR, suggestGases(33500, maxEndMm = null)?.bottom)
        assertEquals(ean32, suggestGases(33500, maxEndMm = null, water = WaterColumn(water = Water.FRESH))?.bottom)
    }

    @Test
    fun imperialStopsSwitchDecoGasAtTenFootSteps() {
        // EAN50 reaches 1.6 bar at 21.6 m: a 21 m stop, or 70 ft (21.3 m) in feet.
        assertEquals(listOf(ean50, BreathingGas.OXYGEN), suggestGases(21200)?.deco)
        assertEquals(listOf(BreathingGas.OXYGEN), suggestGases(21200, imperialStops = true)?.deco)
    }

    @Test
    fun breathableBottomGasGetsNoTrimixDecoGas() {
        // 18/45 is breathable at the surface, so 21/35 would only cover 51 m to 21 m.
        assertEquals(GasSuggestion(BreathingGas(180, 450), listOf(ean50, BreathingGas.OXYGEN)), suggestGases(60000))
    }

    @Test
    fun ean32AtThirtyMetres() {
        // EAN32 is 1.29 bar at 30 m and its END is the 30 m limit.
        assertEquals(GasSuggestion(ean32, listOf(ean50, BreathingGas.OXYGEN)), suggestGases(30000))
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
        assertEquals(GasSuggestion(ean32, emptyList()), suggestGases(5000))
    }

    @Test
    fun noMixFitsImpossibleLimits() {
        // At 300 m a 1.6 bar pO2 needs 5 % O2 and a 0 m END 97 % He.
        assertNull(suggestGases(300000, bottomPpO2Mbar = 1600, maxEndMm = 0))
    }
}
