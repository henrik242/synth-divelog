package no.synth.divelog.ui.tools

import no.synth.divelog.core.gas.BreathingGas
import no.synth.divelog.core.gas.Cylinder
import no.synth.divelog.core.gas.TANK_PRESETS
import no.synth.divelog.core.model.units.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DivePlannerStateTest {
    private fun preset(name: String) = TANK_PRESETS.first { it.sizeName == name }

    @Test
    fun cylindersAreOptionalButWhole() {
        val state = DivePlannerState()
        val none = PlannerGas("21", "0")
        val half = PlannerGas("21", "0", "12", "")
        val whole = PlannerGas("50", "0", "7", "200")
        state.gases += listOf(none, half, whole)
        assertFalse(state.cylinderInvalid(none))
        assertTrue(state.cylinderInvalid(half))
        assertFalse(state.cylinderInvalid(whole))
        assertEquals(mapOf(BreathingGas(500) to listOf(Cylinder(7.0, 200.0, BreathingGas(500)))), state.cylinders())
    }

    @Test
    fun cylinderRangeEdges() {
        val state = DivePlannerState()
        fun invalid(size: String, pressure: String) = state.cylinderInvalid(PlannerGas("21", "0", size, pressure))
        assertFalse(invalid("100", "400"))
        assertTrue(invalid("100.1", "200"))
        assertTrue(invalid("12", "400.1"))
        assertTrue(invalid("0", "200"))
        assertTrue(invalid("12", "abc"))
        state.useUnits(UnitSystem.IMPERIAL)
        assertFalse(invalid("80", "3000"))
        assertFalse(invalid("400", "5800"))
        // Past 400 bar, and an absurd water volume, as in metric.
        assertTrue(invalid("80", "5900"))
        assertTrue(invalid("1000", "100"))
    }

    @Test
    fun aMixListedTwiceHasBothCylinders() {
        val state = DivePlannerState()
        state.gases += PlannerGas("50", "0", "7", "200")
        state.gases += PlannerGas("50", "0", "11", "150")
        assertEquals(listOf(7.0, 11.0), state.cylinders().getValue(BreathingGas(500)).map { it.volumeLitres })
    }

    @Test
    fun theBottomMixListedAgainIsNotADecoGas() {
        val state = DivePlannerState()
        state.gases += PlannerGas("21", "35")
        state.gases += PlannerGas("21", "35")
        state.gases += PlannerGas("50", "0")
        val plan = requireNotNull(state.plan())
        assertEquals(BreathingGas(210, 350), plan.bottomGas)
        assertEquals(listOf(BreathingGas(500)), plan.decoGases)
    }

    @Test
    fun gasesListBottomFirstThenDecoFromTheDeepestSwitch() {
        val state = DivePlannerState()
        state.gases += PlannerGas("100", "0")
        state.gases += PlannerGas("", "0")
        state.gases += PlannerGas("21", "35")
        state.gases += PlannerGas("50", "0")
        assertEquals(listOf("21", "50", "100", ""), state.gasesInOrder().map { it.o2 })
    }

    @Test
    fun aCommonCylinderKeepsItsFiguresAcrossUnits() {
        val state = DivePlannerState().apply { gases += PlannerGas("32", "0", "11.1", "207") }
        val gas = state.gases.single()
        state.useUnits(UnitSystem.IMPERIAL)
        // The S80 is an AL80 in imperial.
        assertEquals("80" to "3000", gas.size to gas.pressure)
        state.useUnits(UnitSystem.METRIC)
        assertEquals("11.1" to "207", gas.size to gas.pressure)
    }

    @Test
    fun anOddCylinderConvertsWithoutDrift() {
        val state = DivePlannerState().apply { gases += PlannerGas("32", "0", "13", "250") }
        val gas = state.gases.single()
        state.useUnits(UnitSystem.IMPERIAL)
        assertEquals("3626", gas.pressure)
        val cylinder = state.cylinders().values.single().single()
        assertEquals(13.0, cylinder.volumeLitres, 0.05)
        assertEquals(250.0, cylinder.pressureBar, 0.01)
        repeat(4) {
            state.useUnits(UnitSystem.METRIC)
            state.useUnits(UnitSystem.IMPERIAL)
        }
        assertEquals("3626", gas.pressure)
        state.useUnits(UnitSystem.METRIC)
        assertEquals("13" to "250", gas.size to gas.pressure)
    }

    @Test
    fun psiSurvivesARoundTrip() {
        val state = DivePlannerState().apply { useUnits(UnitSystem.IMPERIAL) }
        val gas = PlannerGas("32", "0", "50", "2400")
        state.gases += gas
        state.useUnits(UnitSystem.METRIC)
        assertEquals("165.47", gas.pressure)
        state.useUnits(UnitSystem.IMPERIAL)
        assertEquals("50" to "2400", gas.size to gas.pressure)
    }

    @Test
    fun halfACylinderOnAUnitSwitch() {
        val state = DivePlannerState()
        val sizeOnly = PlannerGas("21", "0", "12", "")
        val fillOnly = PlannerGas("50", "0", "", "200")
        state.gases += listOf(sizeOnly, fillOnly)
        state.useUnits(UnitSystem.IMPERIAL)
        // Litres cannot become cuft without a fill.
        assertEquals("" to "", sizeOnly.size to sizeOnly.pressure)
        assertEquals("" to "2901", fillOnly.size to fillOnly.pressure)
    }

    @Test
    fun aTwinPresetIsTheTotal() {
        val state = DivePlannerState()
        val gas = PlannerGas("21", "35")
        state.choose(gas, preset("Twin 12 L 232 bar"))
        assertEquals("24" to "232", gas.size to gas.pressure)
    }

    @Test
    fun choosingInImperialUnits() {
        val state = DivePlannerState().apply { useUnits(UnitSystem.IMPERIAL) }
        val gas = PlannerGas("21", "35")
        state.choose(gas, preset("AL80 (80 cuft, 3000 psi)"))
        assertEquals("80" to "3000", gas.size to gas.pressure)
        state.choose(gas, preset("12 L 232 bar"))
        assertEquals("97" to "3365", gas.size to gas.pressure)
        state.choose(gas, preset("Twin 12 L 232 bar"))
        assertEquals("194.1" to "3365", gas.size to gas.pressure)
        state.gases += gas
        assertEquals(24.0, state.cylinders().values.single().single().volumeLitres, 0.05)
    }

    @Test
    fun theReserveIsAThirdAFixedPressureOrNone() {
        val state = DivePlannerState()
        val cylinder = Cylinder(12.0, 232.0, BreathingGas.AIR)
        assertEquals(232.0 / 3, state.reserveBar(cylinder))
        state.reserve = Reserve.FIXED
        assertEquals(50.0, state.reserveBar(cylinder))
        state.useUnits(UnitSystem.IMPERIAL)
        assertEquals(48.26, state.reserveBar(cylinder), 0.01)
        state.reserve = Reserve.NONE
        assertEquals(0.0, state.reserveBar(cylinder))
    }

    @Test
    fun formats() {
        assertEquals("59 min", hoursMinutes(3540))
        assertEquals("1 h", hoursMinutes(3541))
        assertEquals("1 h 1 min", hoursMinutes(3601 + 59))
        assertEquals("21 h 52 min", hoursMinutes(78680))
        assertEquals("", cylinderText("", "", UnitSystem.METRIC))
        assertEquals("24 L 232 bar", cylinderText("24", "232", UnitSystem.METRIC))
        assertEquals("80 cuft 3000", cylinderText("80", "3000", UnitSystem.IMPERIAL))
        assertEquals("80 cuft 3000 psi", cylinderText(Cylinder(11.1, 206.84, BreathingGas.AIR), UnitSystem.IMPERIAL))
        val stage = Cylinder(7.0, 200.0, BreathingGas(500))
        assertEquals("2 x 7 L 200 bar", cylindersText(listOf(stage, stage), UnitSystem.METRIC))
        assertEquals("7 L 200 bar + 11 L 150 bar", cylindersText(listOf(stage, Cylinder(11.0, 150.0, BreathingGas(500))), UnitSystem.METRIC))
        assertEquals("53 112", grouped(53112))
        assertEquals("999", grouped(999))
        assertEquals("1 000 000", grouped(1_000_000))
    }
}
