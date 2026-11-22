package no.synth.divelog.ui.tools

import no.synth.divelog.core.gas.BreathingGas
import no.synth.divelog.core.gas.Cylinder
import no.synth.divelog.core.model.units.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DivePlannerStateTest {
    private fun plannerPreset(name: String) = PLANNER_PRESETS.first { it.name == name }

    @Test
    fun theMenuOffersTheUsualCylinders() {
        assertEquals(
            listOf("D12 232 bar", "D7 300 bar", "D8.5 232 bar", "S80 200 bar", "S40 200 bar", "7 L 200 bar"),
            PLANNER_PRESETS.map { it.name },
        )
    }

    @Test
    fun cylindersAreOptionalButWhole() {
        val state = DivePlannerState()
        val none = PlannerGas("21", "0")
        val half = PlannerGas("21", "0", "12", "")
        val whole = PlannerGas("50", "0", "7", "200")
        state.gases += listOf(none, half, whole)
        assertFalse(state.cylinderInvalid(none.cylinder))
        assertTrue(state.cylinderInvalid(half.cylinder))
        assertFalse(state.cylinderInvalid(whole.cylinder))
        assertEquals(mapOf(BreathingGas(500) to listOf(Cylinder(7.0, 200.0, BreathingGas(500)))), state.cylinders())
    }

    @Test
    fun cylinderRangeEdges() {
        val state = DivePlannerState()
        fun invalid(size: String, pressure: String) = state.cylinderInvalid(PlannerCylinder(size, pressure))
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
        state.choose(gas.cylinder, plannerPreset("D12 232 bar"))
        assertEquals("24" to "232", gas.size to gas.pressure)
        state.choose(gas.cylinder, plannerPreset("D8.5 232 bar"))
        assertEquals("17" to "232", gas.size to gas.pressure)
    }

    @Test
    fun choosingInImperialUnits() {
        val state = DivePlannerState().apply { useUnits(UnitSystem.IMPERIAL) }
        val gas = PlannerGas("21", "35")
        state.choose(gas.cylinder, plannerPreset("D12 232 bar"))
        assertEquals("194.1" to "3365", gas.size to gas.pressure)
        assertTrue(state.isPreset(gas.cylinder, plannerPreset("D12 232 bar")))
        assertFalse(state.isPreset(gas.cylinder, plannerPreset("D8.5 232 bar")))
        state.gases += gas
        assertEquals(24.0, state.cylinders().values.single().single().volumeLitres, 0.05)
    }

    @Test
    fun theReserveIsAThirdAFixedPressureOrNone() {
        val state = DivePlannerState()
        val cylinder = Cylinder(12.0, 232.0, BreathingGas.AIR)
        assertEquals(0.0, state.reserveBar(cylinder))
        state.reserve = Reserve.THIRDS
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
        assertEquals("53 112", grouped(53112))
        assertEquals("999", grouped(999))
        assertEquals("1 000 000", grouped(1_000_000))
    }

    @Test
    fun settingsFoldToASummary() {
        val state = DivePlannerState()
        assertFalse(state.settingsExpanded)
        assertTrue(state.settingsValid())
        assertEquals("GF 30/75, pO2 1.4/1.6, SAC 20/17 L/min, no reserve, salt water", state.settingsSummary())
        state.reserve = Reserve.THIRDS
        assertEquals("GF 30/75, pO2 1.4/1.6, SAC 20/17 L/min, reserve 1/3, salt water", state.settingsSummary())
        state.gfLow = "90"
        assertFalse(state.settingsValid())
        state.gfLow = "30"
        state.maxEnd = ""
        assertTrue(state.settingsValid())
        state.surfaceMbar = "2000"
        assertFalse(state.settingsValid())
    }

    @Test
    fun anAddedGasStartsInTheDefaultCylinderForItsRole() {
        val state = DivePlannerState()
        state.bottomCylinder.setTo(PlannerCylinder("24", "232"))
        state.decoCylinder.setTo(PlannerCylinder("7", "200"))
        val bottom = PlannerGas("21", "35")
        val deco = PlannerGas("50", "0")
        state.addGas(bottom)
        state.addGas(deco)
        assertEquals("24" to "232", bottom.size to bottom.pressure)
        assertEquals("7" to "200", deco.size to deco.pressure)
        // A cylinder of its own is kept.
        val own = PlannerGas("100", "0", "3", "200")
        state.addGas(own)
        assertEquals("3" to "200", own.size to own.pressure)
    }

    @Test
    fun aNewBottomGasTakesTheBottomCylinderFromTheOldOne() {
        val state = DivePlannerState()
        state.bottomCylinder.setTo(PlannerCylinder("24", "232"))
        state.decoCylinder.setTo(PlannerCylinder("7", "200"))
        val first = PlannerGas("50", "0")
        state.addGas(first)
        assertEquals("24" to "232", first.size to first.pressure)
        state.addGas(PlannerGas("21", "35"))
        assertEquals("7" to "200", first.size to first.pressure)
        // A cylinder the user changed stays when a leaner gas comes along.
        first.size = "11"
        state.addGas(PlannerGas("18", "45"))
        assertEquals("11" to "200", first.size to first.pressure)
    }

    @Test
    fun anotherCylinderOfTheBottomMixIsABottomCylinder() {
        val state = DivePlannerState()
        state.bottomCylinder.setTo(PlannerCylinder("12", "232"))
        state.decoCylinder.setTo(PlannerCylinder("7", "200"))
        val first = PlannerGas("10", "70")
        val second = PlannerGas("10", "70")
        state.addGas(first)
        state.addGas(second)
        assertEquals("12" to "232", first.size to first.pressure)
        assertEquals("12" to "232", second.size to second.pressure)
        assertEquals(2, state.cylinders().values.single().size)
    }

    @Test
    fun cylindersOfOneMixShareTheirLabel() {
        val state = DivePlannerState()
        val gases = listOf(PlannerGas("10", "70"), PlannerGas("50", "0"), PlannerGas("10", "70"), PlannerGas("100", "0"), PlannerGas("50", "0"))
        gases.forEach { state.addGas(it) }
        assertEquals(listOf("Bottom", "Deco 1", "Bottom", "Deco 2", "Deco 1"), gases.map { state.gasLabels().getValue(it) })
    }

    @Test
    fun withoutDefaultsAddedGasesHaveNoCylinder() {
        val state = DivePlannerState()
        state.addGas(PlannerGas("21", "35"))
        state.addGas(PlannerGas("50", "0"))
        assertTrue(state.gases.none { it.hasCylinder() })
    }

    @Test
    fun defaultCylindersFollowTheUnitsAndShowInTheSummary() {
        val state = DivePlannerState()
        state.bottomCylinder.setTo(PlannerCylinder("11.1", "207"))
        assertTrue(state.settingsSummary().endsWith(", bottom 11.1 L 207 bar"), state.settingsSummary())
        state.useUnits(UnitSystem.IMPERIAL)
        assertEquals("80" to "3000", state.bottomCylinder.size to state.bottomCylinder.pressure)
        assertFalse(state.decoCylinder.isSet())
        state.decoCylinder.setTo(PlannerCylinder("40", ""))
        assertFalse(state.settingsValid())
    }

    @Test
    fun suggestionFitsTheDeepestLevelAndKeepsCylinders() {
        val state = DivePlannerState()
        state.levels[0].depth = "30"
        state.levels += PlannerLevel("45", "10")
        state.gases += PlannerGas("32", "0", "24", "232")
        state.gases += PlannerGas("100", "0", "7", "200")
        state.apply(requireNotNull(state.gasSuggestion()))
        assertEquals(listOf(BreathingGas(210, 350), BreathingGas(500), BreathingGas.OXYGEN), state.gases.map { it.parsed() })
        assertEquals(listOf("24", "", "7"), state.gases.map { it.size })
        assertEquals(BreathingGas(210, 350), requireNotNull(state.plan()).bottomGas)
    }

    @Test
    fun suggestedGasesWithoutAnOldCylinderTakeTheDefaults() {
        val state = DivePlannerState()
        state.bottomCylinder.setTo(PlannerCylinder("24", "232"))
        state.decoCylinder.setTo(PlannerCylinder("7", "200"))
        state.apply(requireNotNull(state.gasSuggestion()))
        assertEquals("24" to "232", state.gases[0].size to state.gases[0].pressure)
        assertTrue(state.gases.drop(1).all { it.size == "7" && it.pressure == "200" })
    }

    @Test
    fun noSuggestionWhileALevelIsInvalid() {
        val state = DivePlannerState()
        state.levels[0].depth = "x"
        assertEquals(null, state.gasSuggestion())
        state.useUnits(UnitSystem.IMPERIAL)
        state.levels[0].depth = "140"
        assertEquals(BreathingGas(210, 350), state.gasSuggestion()?.bottom)
    }
}
