package no.synth.divelog.core.gas

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Ranges first ported from the scuba-tools web tank calculator's suite, with the gas weight now
 * from real air (Z about 1.06 at 232 bar) and sea water at 1.03 kg/l like the planner.
 */
class TankBuoyancyTest {
    private val salt = TankSetup(metal = TankMetal.STEEL, saltWater = true, valve = true, doubles = false)
    private val saltAlu = salt.copy(metal = TankMetal.ALUMINIUM)

    private fun assertBetween(low: Double, high: Double, actual: Double, what: String) =
        assertTrue(actual > low && actual < high, "$what $actual not in ($low, $high)")

    // Metric steel

    @Test
    fun steel12L232Bar() {
        val r = buoyancy(MetricTank(12.0, 232.0, 14.5), salt)
        assertBetween(-3.0, -0.5, r.emptyKg, "empty")
        assertTrue(r.fullKg < r.emptyKg)
        assertBetween(3.0, 3.8, r.gasKg, "air")
    }

    @Test
    fun steel15L232Bar() {
        val r = buoyancy(MetricTank(15.0, 232.0, 16.8), salt)
        assertBetween(-1.0, 1.0, r.emptyKg, "empty")
        assertBetween(3.7, 4.3, r.gasKg, "air")
    }

    @Test
    fun twin12LSteelWithManifold() {
        val r = buoyancy(MetricTank(12.0, 232.0, 14.5), salt.copy(doubles = true))
        assertEquals(29.0, r.totalKg)
        assertEquals(24.0, r.totalLitres)
        assertTrue(r.emptyKg < -2)
        assertBetween(6.0, 7.0, r.gasKg, "air")
    }

    @Test
    fun steel10L300Bar() {
        val r = buoyancy(MetricTank(10.0, 300.0, 14.2), salt)
        assertTrue(r.emptyKg < 0)
        // Ideal gas would say 3.7 kg; real air at 300 bar has Z 1.11.
        assertBetween(3.1, 3.5, r.gasKg, "air")
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
        assertBetween(1.7, 2.1, r.gasKg, "air")
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
        val imperial = buoyancy(ImperialTank(80.0, 3000.0, 31.4), saltAlu)
        assertTrue(abs(metric.emptyKg - imperial.emptyKg) < 0.2)
        assertTrue(abs(metric.fullKg - imperial.fullKg) < 0.2)
        // Converting is lossless: the same tank either way.
        val al80 = ImperialTank(80.0, 3000.0, 31.4)
        val viaMetric = al80.toMetric().toImperial()
        assertTrue(abs(viaMetric.cubicFeet - al80.cubicFeet) < 1e-9 && abs(viaMetric.psi - al80.psi) < 1e-9)
        assertEquals(buoyancy(al80, saltAlu).fullKg, buoyancy(al80.toMetric(), saltAlu).fullKg)
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

    // Exact values

    @Test
    fun steel12L232BarExactly() {
        // Displaces (12 + 14.5 / 7.85 + 0.9 / 7.85) l x 1.03 = 14.38 kg; holds 12 x (233.01 / 1.0575
        // - 1.013) / 1.013 = 2598 l of free air, 3.18 kg.
        val r = buoyancy(MetricTank(12.0, 232.0, 14.5), salt)
        assertEquals(listOf(-1.0, -4.2, -2.2, -9.3, 14.5, 12.0), listOf(r.emptyKg, r.fullKg, r.emptyLbs, r.fullLbs, r.totalKg, r.totalLitres))
        val twin = buoyancy(MetricTank(12.0, 232.0, 14.5), salt.copy(doubles = true))
        assertEquals(listOf(-3.3, -9.7), listOf(twin.emptyKg, twin.fullKg))
    }

    @Test
    fun al80HoldsItsRealCapacity() {
        // Labelled 80 cuft (ideal gas), the real-gas capacity is about 77 cuft: 5.9 lbs of air.
        val r = buoyancy(ImperialTank(80.0, 3000.0, 31.4), saltAlu)
        assertEquals(listOf(4.1, -1.8, 1.8, -0.8), listOf(r.emptyLbs, r.fullLbs, r.emptyKg, r.fullKg))
        assertTrue(r.steps.any { it.result == "76.8625 cuft" })
    }

    // Worked calculation

    @Test
    fun explainsTheCalculation() {
        val r = buoyancy(MetricTank(12.0, 232.0, 14.5), salt)
        val expected = listOf(
            "Steel has a density of 7.85 kg/liter",
            "The volume of the tank metal is 14.5 kg / 7.85 = 1.8471 liters",
            "The volume of the valve is 0.9 kg / 7.85 = 0.1146 liters",
            "The density of salt water is 1.03 kg/liter",
            "Total weight in water: (12 + 1.8471 + 0.1146) x 1.03 = 14.3806 kg",
            "Air at 233.0133 bar absolute is less compressible than an ideal gas: Z = 1.0575",
            "Free air in a full tank: 12 liters x (233.0133 bar / 1.0575 - 1.01325 bar) / 1.01325 = 2597.535 liters",
            "Air has a density of 0.001225 kg/liter at 1 atm",
            "The air in a full tank weighs 0.001225 x 2597.535 liters = 3.182 kg",
            "Tank buoyancy when empty: 14.3806 - 14.5 - 0.9 = -1 kg",
            "Tank buoyancy when full: 14.3806 - 14.5 - 0.9 - 3.182 = -4.2 kg",
        )
        assertEquals(expected, r.steps.map { s -> s.result?.let { "${s.text} = $it" } ?: s.text })
    }
}
