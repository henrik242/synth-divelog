package no.synth.divelog.ui.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import no.synth.divelog.core.model.units.UnitSystem

/** A plan the user kept, as its inputs. */
class SavedPlan(val name: String, val inputs: JsonObject)

/** The planner's inputs as typed, for keeping between runs and in the saved list. */
internal fun DivePlannerState.toJson(): JsonObject = JsonObject(
    mapOf(
        "units" to JsonPrimitive(units.name),
        "levels" to JsonArray(levels.map { JsonArray(listOf(JsonPrimitive(it.depth), JsonPrimitive(it.minutes))) }),
        "gases" to JsonArray(gases.map { g -> JsonArray(listOf(g.o2, g.he, g.size, g.pressure).map { JsonPrimitive(it) }) }),
        "bottomPpO2" to JsonPrimitive(bottomPpO2),
        "decoPpO2" to JsonPrimitive(decoPpO2),
        "gfLow" to JsonPrimitive(gfLow),
        "gfHigh" to JsonPrimitive(gfHigh),
        "descentRate" to JsonPrimitive(descentRate),
        "ascentRate" to JsonPrimitive(ascentRate),
        "lastAscentRate" to JsonPrimitive(lastAscentRate),
        "lastStopDeep" to JsonPrimitive(lastStopDeep),
        "saltWater" to JsonPrimitive(saltWater),
        "wholeMinuteStops" to JsonPrimitive(wholeMinuteStops),
        "surfaceMbar" to JsonPrimitive(surfaceMbar),
        "bottomSac" to JsonPrimitive(bottomSac),
        "decoSac" to JsonPrimitive(decoSac),
        "maxEnd" to JsonPrimitive(maxEnd),
        "reserve" to JsonPrimitive(reserve.name),
        "bottomCylinder" to JsonArray(listOf(JsonPrimitive(bottomCylinder.size), JsonPrimitive(bottomCylinder.pressure))),
        "decoCylinder" to JsonArray(listOf(JsonPrimitive(decoCylinder.size), JsonPrimitive(decoCylinder.pressure))),
    ),
)

/** Restores inputs written by [toJson]; anything missing keeps its current value. */
internal fun DivePlannerState.load(json: JsonObject) {
    fun text(key: String) = (json[key] as? JsonPrimitive)?.contentOrNull
    fun flag(key: String) = (json[key] as? JsonPrimitive)?.booleanOrNull
    fun rows(key: String): List<List<String>>? = (json[key] as? JsonArray)?.mapNotNull { item ->
        (item as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    }
    fun pairs(key: String): List<Pair<String, String>>? = rows(key)?.mapNotNull { if (it.size == 2) it[0] to it[1] else null }

    text("units")?.let { name -> UnitSystem.entries.firstOrNull { it.name == name } }?.let { units = it }
    pairs("levels")?.takeIf { it.isNotEmpty() }?.let { list ->
        levels.clear()
        list.forEach { (depth, minutes) -> levels += PlannerLevel(depth, minutes) }
    }
    // O2 and He, then the cylinder's size and fill (missing in plans saved before cylinders).
    // A row without a mix is skipped; when every row is, the gases are kept as they are.
    rows("gases")?.let { all ->
        val list = all.filter { it.size >= 2 }
        if (list.isEmpty() && all.isNotEmpty()) return@let
        gases.clear()
        list.forEach { gases += PlannerGas(it[0], it[1], it.getOrElse(2) { "" }, it.getOrElse(3) { "" }) }
    }
    text("bottomPpO2")?.let { bottomPpO2 = it }
    text("decoPpO2")?.let { decoPpO2 = it }
    text("gfLow")?.let { gfLow = it }
    text("gfHigh")?.let { gfHigh = it }
    text("descentRate")?.let { descentRate = it }
    text("ascentRate")?.let { ascentRate = it }
    text("lastAscentRate")?.let { lastAscentRate = it }
    flag("lastStopDeep")?.let { lastStopDeep = it }
    flag("saltWater")?.let { saltWater = it }
    flag("wholeMinuteStops")?.let { wholeMinuteStops = it }
    text("surfaceMbar")?.let { surfaceMbar = it }
    text("bottomSac")?.let { bottomSac = it }
    text("decoSac")?.let { decoSac = it }
    text("maxEnd")?.let { maxEnd = it }
    text("reserve")?.let { name -> Reserve.entries.firstOrNull { it.name == name } }?.let { reserve = it }
    fun cylinder(key: String) = (json[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }?.takeIf { it.size == 2 }
    cylinder("bottomCylinder")?.let { (size, pressure) -> bottomCylinder.setTo(PlannerCylinder(size, pressure)) }
    cylinder("decoCylinder")?.let { (size, pressure) -> decoCylinder.setTo(PlannerCylinder(size, pressure)) }
}

internal fun encodeSavedPlans(plans: List<SavedPlan>): String =
    JsonArray(plans.map { JsonObject(mapOf("name" to JsonPrimitive(it.name), "inputs" to it.inputs)) }).toString()

/** Saved plans from [encodeSavedPlans]; unreadable text gives none. */
internal fun decodeSavedPlans(text: String?): List<SavedPlan> =
    parse(text)?.let { runCatching { it.jsonArray }.getOrNull() }.orEmpty().mapNotNull { item ->
        val obj = runCatching { item.jsonObject }.getOrNull() ?: return@mapNotNull null
        val name = runCatching { obj["name"]?.jsonPrimitive?.contentOrNull }.getOrNull() ?: return@mapNotNull null
        val inputs = runCatching { obj["inputs"]?.jsonObject }.getOrNull() ?: return@mapNotNull null
        SavedPlan(name, inputs)
    }

/** The planner inputs from [text], or null when there are none or they cannot be read. */
internal fun decodePlannerInputs(text: String?): JsonObject? = parse(text)?.let { runCatching { it.jsonObject }.getOrNull() }

private fun parse(text: String?): JsonElement? = text?.let { runCatching { Json.parseToJsonElement(it) }.getOrNull() }
