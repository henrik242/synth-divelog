package no.synth.divelog.core.model.units

import kotlin.test.Test
import kotlin.test.assertEquals

class UnitsTest {
    private fun assertClose(expected: Double, actual: Double, tol: Double = 1e-6) {
        assertEquals(expected, actual, tol)
    }

    @Test
    fun depthConversions() {
        assertClose(30.0, Units.depthMetres(30_000))
        assertClose(98.4252, Units.depthFeet(30_000), tol = 1e-3)
    }

    @Test
    fun pressureConversions() {
        assertClose(200.0, Units.pressureBar(200_000))
        assertClose(2900.75, Units.pressurePsi(200_000), tol = 1e-1)
    }

    @Test
    fun temperatureConversions() {
        // 283.15 K = 10 C = 50 F
        assertClose(10.0, Units.temperatureCelsius(283_150))
        assertClose(50.0, Units.temperatureFahrenheit(283_150))
    }

    @Test
    fun gasConversions() {
        assertClose(32.0, Units.gasPercent(320))
        assertClose(0.32, Units.gasFraction(320))
    }

    @Test
    fun durationParts() {
        assertEquals(42, Units.durationMinutesPart(2_530))
        assertEquals(10, Units.durationSecondsPart(2_530))
    }

    @Test
    fun systemAwareSelectorPicksUnitAndValue() {
        val metric = Units.depth(30_000, UnitSystem.METRIC)
        assertEquals("m", metric.unit)
        assertClose(30.0, metric.value)

        val imperial = Units.temperature(283_150, UnitSystem.IMPERIAL)
        assertEquals("°F", imperial.unit)
        assertClose(50.0, imperial.value)
    }

    @Test
    fun imperialTankSizeIsNominalGasAtWorkingPressure() {
        // An AL80: 11.1 L of water at 3000 psi holds 80 cuft of free gas.
        val al80 = Units.tankSize(11_100, 206_843, UnitSystem.IMPERIAL)
        assertEquals("cuft", al80.unit)
        assertClose(80.0, al80.value, 0.05)
        assertEquals(11_100, Units.tankVolumeMl(al80.value, 206_843, UnitSystem.IMPERIAL))
        assertClose(11_100.0, Units.tankVolumeMl(80.0, 206_843, UnitSystem.IMPERIAL).toDouble(), 10.0)
    }

    @Test
    fun tankSizeWithoutWorkingPressureIsLitres() {
        assertEquals(Measure(12.0, "L"), Units.tankSize(12_000, null, UnitSystem.IMPERIAL))
        assertEquals(Measure(12.0, "L"), Units.tankSize(12_000, 232_000, UnitSystem.METRIC))
        assertEquals(12_000, Units.tankVolumeMl(12.0, null, UnitSystem.IMPERIAL))
    }
}
