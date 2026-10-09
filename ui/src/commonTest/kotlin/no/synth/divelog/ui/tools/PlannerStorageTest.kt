package no.synth.divelog.ui.tools

import no.synth.divelog.core.model.units.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerStorageTest {
    private fun sample() = DivePlannerState().apply {
        levels.clear()
        levels += PlannerLevel("100", "35")
        gases += PlannerGas("10", "70")
        gases += PlannerGas("50", "0")
        gfLow = "20"
        gfHigh = "85"
        wholeMinuteStops = false
        saltWater = false
    }

    @Test
    fun inputsSurviveARoundTrip() {
        val original = sample()
        val restored = DivePlannerState().apply { load(original.toJson()) }
        assertEquals(original.toJson(), restored.toJson())
        assertEquals("100", restored.levels.single().depth)
        assertEquals(listOf("10" to "70", "50" to "0"), restored.gases.map { it.o2 to it.he })
        assertEquals(false, restored.wholeMinuteStops)
    }

    @Test
    fun wholeMinuteStopsAreTheDefault() {
        assertTrue(DivePlannerState().wholeMinuteStops)
    }

    @Test
    fun savedPlansSurviveARoundTrip() {
        val plans = listOf(SavedPlan("Deep", sample().toJson()), SavedPlan("Shallow", DivePlannerState().toJson()))
        val back = decodeSavedPlans(encodeSavedPlans(plans))
        assertEquals(plans.map { it.name to it.inputs }, back.map { it.name to it.inputs })
    }

    @Test
    fun unreadableTextGivesNothing() {
        assertNull(decodePlannerInputs("not json"))
        assertNull(decodePlannerInputs(""))
        assertEquals(emptyList(), decodeSavedPlans("{oops"))
        assertEquals(emptyList(), decodeSavedPlans(null))
    }

    @Test
    fun summaryNamesTheDive() {
        assertEquals("100 m 35 min 10/70", sample().summary())
        assertEquals("Plan", DivePlannerState().apply { levels.clear() }.summary())
    }

    @Test
    fun loadKeepsTheUnitsItWasSavedIn() {
        val imperial = DivePlannerState().apply { units = UnitSystem.IMPERIAL }
        assertEquals(UnitSystem.IMPERIAL, DivePlannerState().apply { load(imperial.toJson()) }.units)
    }
}
