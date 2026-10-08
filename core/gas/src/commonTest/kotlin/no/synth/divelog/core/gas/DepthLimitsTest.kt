package no.synth.divelog.core.gas

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Sea water is 1.03 kg/l under 1013 mbar: 101.04 mbar per metre, as in the planner. */
class DepthLimitsTest {
    private val sea = DepthLimits()
    private val ean32 = BreathingGas(320)
    private val trimix1845 = BreathingGas(180, 450)

    private fun assertNear(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < 1e-9, "expected $expected, was $actual")

    @Test
    fun modOfCommonMixes() {
        assertEquals(33.2, sea.mod(ean32, 1.4)) // 33.27, rounded down
        assertEquals(39.4, sea.mod(ean32, 1.6)) // 39.46
        assertEquals(21.6, sea.mod(BreathingGas(500), 1.6)) // 21.64
        assertEquals(5.8, sea.mod(BreathingGas.OXYGEN, 1.6)) // 5.81
        assertEquals(55.9, sea.mod(BreathingGas.AIR, 1.4)) // 55.95
    }

    @Test
    fun modInFeetFreshWaterAndAltitude() {
        assertEquals(109.1, DepthLimits(unit = DepthUnit.FEET).mod(ean32, 1.4)) // 109.16
        assertEquals(34.2, DepthLimits(WaterColumn(1013, Water.FRESH)).mod(ean32, 1.4)) // 34.27
        // A lake at altitude: less air above, so deeper for the same pO2.
        assertTrue(DepthLimits(WaterColumn(800, Water.FRESH)).mod(ean32, 1.4) > 36.0)
    }

    @Test
    fun modMatchesThePlannersWater() {
        // Same water model as the planner: EAN32 reaches 1.4 bar at 33.27 m in both.
        assertEquals(33273, WaterColumn().depthMm(1400 * 1000 / 320))
        assertEquals(33.2, sea.mod(ean32, 1.4))
        // EAN50 at 1.6 (21.64 m) is switched to at the 21 m stop.
        assertEquals(21000, WaterColumn().switchDepthMm(BreathingGas(500), 1600, 3000))
    }

    @Test
    fun minimumDepthOfHypoxicMixes() {
        assertEquals(5.9, sea.minimumDepth(BreathingGas(100))) // 5.81, rounded up
        assertEquals(0.0, sea.minimumDepth(BreathingGas.AIR))
        assertEquals(0.0, sea.minimumDepth(BreathingGas(160)))
        assertEquals(4.4, sea.minimumDepth(BreathingGas(110))) // 4.37, rounded up
    }

    @Test
    fun endOfAirIsItsDepthEitherWay() {
        assertEquals(30.0, sea.end(BreathingGas.AIR, 30.0, o2Narcotic = true))
        assertEquals(30.0, sea.end(BreathingGas.AIR, 30.0, o2Narcotic = false))
    }

    @Test
    fun endOfTrimix() {
        // 18/45 at 60 m: 0.55 of the ambient pressure is narcotic -> 28.49 m, rounded up.
        assertEquals(28.5, sea.end(trimix1845, 60.0, o2Narcotic = true))
        // N2 only: 0.37 / 0.79 of it -> 22.77 m.
        assertEquals(22.8, sea.end(trimix1845, 60.0, o2Narcotic = false))
    }

    @Test
    fun eadOfNitrox() {
        // EAN32 at 30 m, N2 only: 0.68 / 0.79 of the ambient pressure -> 24.43 m.
        assertEquals(24.5, sea.end(ean32, 30.0, o2Narcotic = false))
        // With O2 narcotic nitrox is no better than air.
        assertEquals(30.0, sea.end(ean32, 30.0, o2Narcotic = true))
    }

    @Test
    fun endNeverAboveTheSurface() {
        assertEquals(0.0, sea.end(BreathingGas(100, 700), 5.0, o2Narcotic = true))
    }

    @Test
    fun depthForEndLimit() {
        assertEquals(30.0, sea.depthForEnd(BreathingGas.AIR, 30.0, o2Narcotic = true))
        assertEquals(62.7, sea.depthForEnd(trimix1845, 30.0, o2Narcotic = true)) // 62.75
        assertEquals(75.4, sea.depthForEnd(trimix1845, 30.0, o2Narcotic = false)) // 75.43
        assertEquals(36.4, sea.depthForEnd(ean32, 30.0, o2Narcotic = false)) // 36.47
        assertEquals(123.3, sea.depthForEnd(BreathingGas(100, 700), 30.0, o2Narcotic = true)) // 123.39
        assertEquals(100.0, DepthLimits(unit = DepthUnit.FEET).depthForEnd(BreathingGas.AIR, 100.0, o2Narcotic = true))
    }

    @Test
    fun noEndLimitWithoutNarcoticGas() {
        assertEquals(null, sea.depthForEnd(BreathingGas(210, 790), 30.0, o2Narcotic = false))
    }

    @Test
    fun ppO2AtDepth() {
        assertNear(0.32 * (1.013 + 30 * 0.101043), sea.ppO2(ean32, 30.0))
        assertNear(0.21 * 1.013, sea.ppO2(BreathingGas.AIR, 0.0))
    }
}
