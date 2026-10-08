package no.synth.divelog.core.gas

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DepthLimitsTest {
    private val sea = WaterScale.METRES_SALT

    private fun assertNear(expected: Double, actual: Double) =
        assertTrue(abs(expected - actual) < 1e-9, "expected $expected, was $actual")

    @Test
    fun modOfCommonMixes() {
        assertEquals(33.7, DepthLimits.mod(32.0, 1.4, sea)) // 33.75, rounded down
        assertEquals(40.0, DepthLimits.mod(32.0, 1.6, sea))
        assertEquals(22.0, DepthLimits.mod(50.0, 1.6, sea))
        assertEquals(6.0, DepthLimits.mod(100.0, 1.6, sea))
        assertEquals(56.6, DepthLimits.mod(21.0, 1.4, sea))
    }

    @Test
    fun modInFeetAndFreshWater() {
        assertEquals(111.3, DepthLimits.mod(32.0, 1.4, WaterScale.FEET_SALT)) // 111.375
        assertEquals(34.7, DepthLimits.mod(32.0, 1.4, WaterScale.METRES_FRESH)) // 34.76
    }

    @Test
    fun minimumDepthOfHypoxicMixes() {
        assertEquals(6.0, DepthLimits.minimumDepth(10.0, sea))
        assertEquals(0.0, DepthLimits.minimumDepth(21.0, sea))
        assertEquals(0.0, DepthLimits.minimumDepth(16.0, sea))
        assertEquals(4.6, DepthLimits.minimumDepth(11.0, sea)) // 4.545, rounded up
    }

    @Test
    fun endOfAirIsItsDepthEitherWay() {
        assertEquals(30.0, DepthLimits.end(21.0, 0.0, 30.0, sea, o2Narcotic = true))
        assertEquals(30.0, DepthLimits.end(21.0, 0.0, 30.0, sea, o2Narcotic = false))
    }

    @Test
    fun endOfTrimix() {
        // 18/45 at 60 m: 7 bar x 0.55 narcotic = 3.85 bar of air -> 28.5 m.
        assertEquals(28.5, DepthLimits.end(18.0, 45.0, 60.0, sea, o2Narcotic = true))
        // N2 only: 7 x 0.37 / 0.79 = 3.278 bar -> 22.8 m (22.78, rounded up).
        assertEquals(22.8, DepthLimits.end(18.0, 45.0, 60.0, sea, o2Narcotic = false))
    }

    @Test
    fun eadOfNitrox() {
        // EAN32 at 30 m, N2 only: 4 x 0.68 / 0.79 = 3.443 bar -> 24.5 m (24.43, rounded up).
        assertEquals(24.5, DepthLimits.end(32.0, 0.0, 30.0, sea, o2Narcotic = false))
        // With O2 narcotic nitrox is no better than air.
        assertEquals(30.0, DepthLimits.end(32.0, 0.0, 30.0, sea, o2Narcotic = true))
    }

    @Test
    fun endNeverAboveTheSurface() {
        assertEquals(0.0, DepthLimits.end(10.0, 70.0, 5.0, sea, o2Narcotic = true))
    }

    @Test
    fun depthForEndLimit() {
        assertEquals(30.0, DepthLimits.depthForEnd(21.0, 0.0, 30.0, sea, o2Narcotic = true))
        // 18/45, END 30 m: 4 bar / 0.55 = 7.27 bar -> 62.7 m; N2 only: 4 x 0.79 / 0.37 -> 75.4 m.
        assertEquals(62.7, DepthLimits.depthForEnd(18.0, 45.0, 30.0, sea, o2Narcotic = true))
        assertEquals(75.4, DepthLimits.depthForEnd(18.0, 45.0, 30.0, sea, o2Narcotic = false))
        // EAN32, EAD 30 m: 4 x 0.79 / 0.68 = 4.647 bar -> 36.4 m.
        assertEquals(36.4, DepthLimits.depthForEnd(32.0, 0.0, 30.0, sea, o2Narcotic = false))
        assertEquals(123.3, DepthLimits.depthForEnd(10.0, 70.0, 30.0, sea, o2Narcotic = true))
        assertEquals(100.0, DepthLimits.depthForEnd(21.0, 0.0, 100.0, WaterScale.FEET_SALT, o2Narcotic = true))
    }

    @Test
    fun noEndLimitWithoutNarcoticGas() {
        assertEquals(null, DepthLimits.depthForEnd(21.0, 79.0, 30.0, sea, o2Narcotic = false))
    }

    @Test
    fun ppO2AtDepth() {
        assertNear(1.28, DepthLimits.ppO2(32.0, 30.0, sea))
        assertNear(0.21, DepthLimits.ppO2(21.0, 0.0, sea))
        assertNear(0.42, DepthLimits.ppO2(21.0, 33.0, WaterScale.FEET_SALT))
    }
}
