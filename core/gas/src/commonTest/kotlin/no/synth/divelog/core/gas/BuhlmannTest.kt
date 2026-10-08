package no.synth.divelog.core.gas

import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuhlmannTest {
    private fun assertNear(expected: Double, actual: Double, tolerance: Double = 1e-9) =
        assertTrue(abs(expected - actual) < tolerance, "expected $expected, was $actual")

    private val surface = 1.013

    @Test
    fun startsSaturatedWithAir() {
        val t = Tissues.atSurface(surface)
        for (c in 0 until Zhl16c.COMPARTMENTS) {
            assertNear((surface - WATER_VAPOUR_BAR) * 0.781, t.n2(c))
            assertEquals(0.0, t.he(c))
        }
        assertNear(surface + 1.0, t.gfLowAnchorBar)
    }

    @Test
    fun halfLifeHalvesTheGradient() {
        val t = Tissues.atSurface(surface)
        val start = t.n2(0)
        val inspired = (4.0 - WATER_VAPOUR_BAR) * 0.79
        t.constantDepth(4.0, BreathingGas.AIR, 5 * 60) // compartment 1: N2 half-life 5 min
        assertNear(start + (inspired - start) / 2, t.n2(0), 1e-6)
        // Compartment 16 (635 min) has barely moved.
        assertTrue(t.n2(15) - start < 0.05)
    }

    @Test
    fun heliumLoadsFasterThanNitrogen() {
        val t = Tissues.atSurface(surface)
        t.constantDepth(5.0, BreathingGas(180, 450), 10 * 60)
        val heInspired = (5.0 - WATER_VAPOUR_BAR) * 0.45
        // He compartment 1 (1.88 min): over five half-lives in, within 3 % of inspired.
        assertTrue(heInspired - t.he(0) < heInspired * 0.03)
    }

    @Test
    fun schreinerMatchesFineSteps() {
        val gas = BreathingGas(210, 350)
        val exact = Tissues.atSurface(surface)
        exact.linearChange(surface, 7.0, gas, 180)
        // 180 one-second constant-pressure steps at the mid-second pressure.
        val stepped = Tissues.atSurface(surface)
        for (i in 0 until 180) stepped.constantDepth(surface + (7.0 - surface) * (i + 0.5) / 180, gas, 1)
        for (c in 0 until 16) {
            assertNear(stepped.n2(c), exact.n2(c), 1e-4)
            assertNear(stepped.he(c), exact.he(c), 1e-4)
        }
    }

    @Test
    fun linearChangeWithoutDepthChangeIsHaldane() {
        val a = Tissues.atSurface(surface)
        val b = Tissues.atSurface(surface)
        a.linearChange(3.0, 3.0, BreathingGas(320), 600)
        b.constantDepth(3.0, BreathingGas(320), 600)
        for (c in 0 until 16) assertNear(a.n2(c), b.n2(c))
    }

    @Test
    fun noCeilingAtTheSurface() {
        assertTrue(Tissues.atSurface(surface).toleratedAmbientBar(GradientFactors(0.3, 0.7), surface) < surface)
    }

    @Test
    fun gf100IsPlainBuhlmann() {
        val t = Tissues.atSurface(surface)
        t.constantDepth(5.0, BreathingGas(210, 350), 30 * 60)
        var expected = 0.0
        for (c in 0 until 16) {
            val n2 = t.n2(c)
            val he = t.he(c)
            val a = (Zhl16c.n2A[c] * n2 + Zhl16c.heA[c] * he) / (n2 + he)
            val b = (Zhl16c.n2B[c] * n2 + Zhl16c.heB[c] * he) / (n2 + he)
            expected = max(expected, (n2 + he - a) * b)
        }
        assertNear(expected, t.toleratedAmbientBar(GradientFactors(1.0, 1.0), surface))
    }

    @Test
    fun gradientFactorsSlopeFromTheAnchor() {
        val t = Tissues.atSurface(surface)
        t.constantDepth(6.0, BreathingGas.AIR, 60 * 60)
        val lowOnly = t.copy().toleratedAmbientBar(GradientFactors(0.3, 0.3), surface)
        val sloped = t.copy().toleratedAmbientBar(GradientFactors(0.3, 0.8), surface)
        val plain = t.copy().toleratedAmbientBar(GradientFactors(1.0, 1.0), surface)
        assertTrue(lowOnly > sloped && sloped > plain, "$lowOnly $sloped $plain")
        // The anchor moves to the GF-low ceiling once that is deeper than surface + 1 bar.
        val anchored = t.copy()
        anchored.toleratedAmbientBar(GradientFactors(0.3, 0.8), surface)
        assertNear(lowOnly, anchored.gfLowAnchorBar, 1e-9)
        // At the anchor itself the sloped ceiling equals the GF-low one.
        assertNear(lowOnly, sloped, 1e-9)
    }

    @Test
    fun copyIsIndependent() {
        val t = Tissues.atSurface(surface)
        val c = t.copy()
        c.constantDepth(4.0, BreathingGas.AIR, 600)
        assertNear((surface - WATER_VAPOUR_BAR) * 0.781, t.n2(0))
    }

    @Test
    fun gasNames() {
        assertEquals("Air", BreathingGas.AIR.name)
        assertEquals("Oxygen", BreathingGas.OXYGEN.name)
        assertEquals("EAN50", BreathingGas(500).name)
        assertEquals("18/45", BreathingGas(180, 450).name)
        assertEquals("15/0", BreathingGas(150).name)
    }
}
