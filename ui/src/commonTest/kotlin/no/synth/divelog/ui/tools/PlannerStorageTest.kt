package no.synth.divelog.ui.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import no.synth.divelog.core.model.units.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlannerStorageTest {
    private fun sample() = DivePlannerState().apply {
        levels.clear()
        levels += PlannerLevel("100", "35")
        gases += PlannerGas("10", "70", "24", "232")
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
        assertEquals(listOf("24" to "232", "" to ""), restored.gases.map { it.size to it.pressure })
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

    @Test
    fun gasesSavedWithoutCylindersStillLoad() {
        val old = Json.parseToJsonElement("""{"gases":[["21","35"],["50","0"]]}""").jsonObject
        val state = DivePlannerState().apply { load(old) }
        assertEquals(listOf("21" to "35", "50" to "0"), state.gases.map { it.o2 to it.he })
        assertTrue(state.gases.none { it.hasCylinder() })
    }

    @Test
    fun aMalformedGasRowIsSkipped() {
        val json = Json.parseToJsonElement("""{"gases":[["21"],["50","0","7"]]}""").jsonObject
        val state = DivePlannerState().apply { load(json) }
        assertEquals(listOf(Triple("50", "0", "7")), state.gases.map { Triple(it.o2, it.he, it.size) })
        assertEquals("", state.gases.single().pressure)
    }

    @Test
    fun onlyMalformedGasRowsKeepTheGases() {
        val state = DivePlannerState().apply { gases += PlannerGas("32", "0") }
        state.load(Json.parseToJsonElement("""{"gases":[["oops"]]}""").jsonObject)
        assertEquals(listOf("32"), state.gases.map { it.o2 })
        state.load(Json.parseToJsonElement("""{"gases":[]}""").jsonObject)
        assertTrue(state.gases.isEmpty())
    }

    @Test
    fun theReserveIsKept() {
        val thirds = DivePlannerState().apply { reserve = Reserve.THIRDS }
        assertEquals(Reserve.THIRDS, DivePlannerState().apply { load(thirds.toJson()) }.reserve)
        assertEquals(Reserve.NONE, DivePlannerState().reserve)
    }

    @Test
    fun defaultCylindersAreKept() {
        val original = DivePlannerState().apply {
            bottomCylinder.setTo(PlannerCylinder("24", "232"))
            decoCylinder.setTo(PlannerCylinder("7", "200"))
        }
        val restored = DivePlannerState().apply { load(original.toJson()) }
        assertEquals("24" to "232", restored.bottomCylinder.size to restored.bottomCylinder.pressure)
        assertEquals("7" to "200", restored.decoCylinder.size to restored.decoCylinder.pressure)
        assertFalse(DivePlannerState().apply { load(DivePlannerState().toJson()) }.bottomCylinder.isSet())
    }
}
