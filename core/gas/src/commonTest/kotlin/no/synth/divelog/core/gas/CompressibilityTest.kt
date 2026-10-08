package no.synth.divelog.core.gas

import no.synth.divelog.core.model.units.ATM_BAR
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

class CompressibilityTest {
    private fun assertNear(expected: Double, actual: Double, tolerance: Double, what: String) =
        assertTrue(abs(expected - actual) <= tolerance, "$what: expected $expected, was $actual")

    private val o2 = BreathingGas.OXYGEN
    private val n2 = BreathingGas(0)
    private val he = BreathingGas.HELIUM

    @Test
    fun matchesSubsurfacesFit() {
        // Subsurface's gas_compressibility_factor, evaluated by hand from its coefficients.
        val expected = mapOf(
            100.0 to Triple(0.9549, 1.0053, 1.0479),
            200.0 to Triple(0.9571, 1.0567, 1.0944),
            300.0 to Triple(0.9977, 1.1417, 1.1397),
        )
        for ((bar, z) in expected) {
            assertNear(z.first, Compressibility.z(o2, bar), 1e-4, "O2 at $bar")
            assertNear(z.second, Compressibility.z(n2, bar), 1e-4, "N2 at $bar")
            assertNear(z.third, Compressibility.z(he, bar), 1e-4, "He at $bar")
        }
    }

    @Test
    fun agreesWithPublishedValues() {
        // Room temperature, 200 bar: N2 about 1.03-1.06, He about 1.09-1.10, O2 just under 1.
        assertTrue(Compressibility.z(n2, 200.0) in 1.03..1.06)
        assertTrue(Compressibility.z(he, 200.0) in 1.09..1.10)
        assertTrue(Compressibility.z(o2, 200.0) in 0.93..0.97)
        // Air is stiffer than ideal at cylinder pressures.
        assertTrue(Compressibility.z(BreathingGas.AIR, 233.0) in 1.04..1.07)
        // Near 1 at the surface.
        assertNear(1.0, Compressibility.z(BreathingGas.AIR, ATM_BAR), 0.001, "air at 1 atm")
    }

    @Test
    fun mixesByMoleFraction() {
        val mix = BreathingGas(180, 450)
        val bar = 232.0
        val linear = 0.18 * Compressibility.z(o2, bar) + 0.45 * Compressibility.z(he, bar) + 0.37 * Compressibility.z(n2, bar)
        assertNear(linear, Compressibility.z(mix, bar), 1e-12, "18/45")
    }

    @Test
    fun clampsToTheFittedRange() {
        assertNear(Compressibility.z(he, 500.0), Compressibility.z(he, 700.0), 1e-12, "He above 500 bar")
    }

    @Test
    fun gaugeBarInvertsIdealBar() {
        for (gauge in listOf(0.0, 50.0, 232.0, 300.0)) {
            val ideal = Compressibility.idealBar(gauge, 0.18, 0.45)
            assertNear(gauge, Compressibility.gaugeBar(ideal, 0.18, 0.45), 1e-5, "gauge $gauge")
        }
    }
}
