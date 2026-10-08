package no.synth.divelog.core.gas

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The scuba-tools web tank calculator's test suite, case for case. */
class TankBuoyancyTest {
    private val salt = TankSetup(metal = TankMetal.STEEL, saltWater = true, valve = true, doubles = false)
    private val saltAlu = salt.copy(metal = TankMetal.ALUMINIUM)

    private fun assertBetween(low: Double, high: Double, actual: Double, what: String) =
        assertTrue(actual > low && actual < high, "$what $actual not in ($low, $high)")

    // Metric steel

    @Test
    fun steel12L232Bar() {
        val r = buoyancy(MetricTank(12.0, 232.0, 14.5), salt)
        assertBetween(-3.0, -1.0, r.emptyKg, "empty")
        assertTrue(r.fullKg < r.emptyKg)
        assertBetween(3.0, 3.8, r.gasKg, "air")
    }

    @Test
    fun steel15L232Bar() {
        val r = buoyancy(MetricTank(15.0, 232.0, 16.8), salt)
        assertBetween(-1.0, 1.0, r.emptyKg, "empty")
        assertBetween(4.0, 4.7, r.gasKg, "air")
    }

    @Test
    fun twin12LSteelWithManifold() {
        val r = buoyancy(MetricTank(12.0, 232.0, 14.5), salt.copy(doubles = true))
        assertEquals(29.0, r.totalKg)
        assertEquals(24.0, r.totalLitres)
        assertTrue(r.emptyKg < -2)
        assertBetween(6.5, 7.5, r.gasKg, "air")
    }

    @Test
    fun steel10L300Bar() {
        val r = buoyancy(MetricTank(10.0, 300.0, 14.2), salt)
        assertTrue(r.emptyKg < 0)
        assertBetween(3.4, 4.0, r.gasKg, "air")
    }

    // Metric aluminium

    @Test
    fun aluminium11L207Bar() {
        val r = buoyancy(MetricTank(11.0, 207.0, 14.2), saltAlu)
        assertBetween(-0.5, 2.5, r.emptyKg, "empty")
        assertTrue(r.fullKg < r.emptyKg)
        assertBetween(2.5, 3.1, r.gasKg, "air")
    }

    @Test
    fun aluminium13L207Bar() {
        val r = buoyancy(MetricTank(13.0, 207.0, 16.5), saltAlu)
        assertBetween(0.5, 3.0, r.emptyKg, "empty")
        assertBetween(3.0, 3.6, r.gasKg, "air")
    }

    // Imperial

    @Test
    fun al80Catalina() {
        val r = buoyancy(ImperialTank(77.4, 3000.0, 31.4), saltAlu)
        assertBetween(2.0, 5.0, r.emptyLbs, "empty")
        assertTrue(r.fullLbs < r.emptyLbs)
        assertBetween(-3.0, 1.0, r.fullLbs, "full")
    }

    @Test
    fun hp100Steel() {
        val r = buoyancy(ImperialTank(100.0, 3442.0, 33.2), salt)
        assertBetween(-4.0, 0.0, r.emptyLbs, "empty")
        assertTrue(r.fullLbs < r.emptyLbs)
        assertBetween(7.0, 8.5, r.emptyLbs - r.fullLbs, "air")
    }

    @Test
    fun hp120Steel() {
        val r = buoyancy(ImperialTank(120.0, 3442.0, 38.9), salt)
        assertBetween(-3.0, 0.0, r.emptyLbs, "empty")
        assertBetween(8.5, 10.0, r.emptyLbs - r.fullLbs, "air")
    }

    // Fresh vs salt water

    @Test
    fun freshWaterIsLessBuoyant() {
        val tank = MetricTank(12.0, 232.0, 14.5)
        val saltResult = buoyancy(tank, salt)
        val fresh = buoyancy(tank, salt.copy(saltWater = false))
        assertTrue(fresh.emptyKg < saltResult.emptyKg)
        assertTrue(fresh.fullKg < saltResult.fullKg)
        assertBetween(0.2, 0.7, saltResult.emptyKg - fresh.emptyKg, "difference")
    }

    // Edge cases

    @Test
    fun withoutValve() {
        val r = buoyancy(MetricTank(12.0, 232.0, 14.5), salt.copy(valve = false))
        assertTrue(!r.emptyKg.isNaN() && !r.fullKg.isNaN())
    }

    @Test
    fun pony3L() {
        val r = buoyancy(MetricTank(3.0, 232.0, 3.2), salt)
        assertTrue(r.emptyKg < 0)
        assertBetween(0.7, 1.0, r.gasKg, "air")
    }

    @Test
    fun stage7L() {
        val r = buoyancy(MetricTank(7.0, 232.0, 8.5), salt)
        assertTrue(r.emptyKg < 0)
        assertBetween(1.8, 2.3, r.gasKg, "air")
    }

    @Test
    fun lowPressure150Bar() {
        val r = buoyancy(MetricTank(15.0, 150.0, 15.2), salt)
        assertBetween(2.5, 3.2, r.gasKg, "air")
    }

    // Unit conversions

    @Test
    fun convertsMetricToImperial() {
        val tank = MetricTank(12.0, 232.0, 14.5)
        val imperial = tank.toImperial()
        assertBetween(3300.0, 3400.0, imperial.psi, "psi")
        assertBetween(31.0, 33.0, imperial.lbs, "lbs")
        val r = buoyancy(tank, salt)
        assertTrue(abs(r.emptyLbs - r.emptyKg * 2.20462) < 0.2)
    }

    @Test
    fun metricAndImperialAgree() {
        val metric = buoyancy(MetricTank(11.1, 207.0, 14.2), saltAlu)
        val imperial = buoyancy(ImperialTank(77.4, 3000.0, 31.4), saltAlu)
        assertTrue(abs(metric.emptyKg - imperial.emptyKg) < 0.5)
        assertTrue(abs(metric.fullKg - imperial.fullKg) < 0.5)
    }

    // Material density

    @Test
    fun aluminiumDisplacesMoreThanSteelOfSameWeight() {
        val tank = MetricTank(12.0, 232.0, 14.5)
        val steel = buoyancy(tank, salt.copy(valve = false))
        val alu = buoyancy(tank, saltAlu.copy(valve = false))
        assertTrue(alu.emptyKg > steel.emptyKg)
        assertBetween(3.0, 6.0, alu.emptyKg - steel.emptyKg, "difference")
    }

    // Worked calculation

    @Test
    fun explainsTheCalculation() {
        val r = buoyancy(MetricTank(12.0, 232.0, 14.5), salt)
        val text = r.steps.joinToString("\n") { it.text }
        assertTrue("density" in text)
        assertTrue("7.85" in text)
        assertTrue("salt water" in text)
        assertTrue("buoyancy" in text)
        // The web version bolds each step's result.
        assertTrue(r.steps.any { it.result != null })
    }
}
