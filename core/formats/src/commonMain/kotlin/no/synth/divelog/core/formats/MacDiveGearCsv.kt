package no.synth.divelog.core.formats

import kotlin.math.roundToLong
import kotlinx.datetime.LocalDate
import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearKind
import no.synth.divelog.core.model.GearService
import no.synth.divelog.core.model.ImportedGear
import no.synth.divelog.core.model.UNSAVED_ID

/**
 * MacDive's gear list as exported to CSV: a header row, then one row per piece with
 * Manufacturer, Name, Type, Serial, Weight (in kilograms), Purchase Date, Purchase Price, Shop,
 * Warranty and Last Service. Columns are found by their header.
 */
object MacDiveGearCsv {
    /** Each piece of gear with its last service, if MacDive had one; the services' gearId is unset. */
    fun parse(text: String): List<ImportedGear> {
        val rows = csvRows(text.removePrefix("\uFEFF")).filter { row -> row.any { it.isNotBlank() } }
        val at = rows.firstOrNull().orEmpty().withIndex().associate { (i, name) -> name.trim().lowercase() to i }
        if (listOf("manufacturer", "name", "type").any { it !in at }) throw FormatException("Not a MacDive gear list")
        return rows.drop(1).map { row ->
            fun field(name: String) = at[name]?.let(row::getOrNull)?.trim()?.ifBlank { null }
            val type = field("type")
            val kind = type?.let(::kindOf)
            val weight = field("weight")?.toDoubleOrNull()?.takeIf { it > 0 }
            val notes = listOfNotNull(
                type?.takeIf { kind == GearKind.OTHER }?.let { "Type: $it" },
                weight?.let { "Weight: ${it.toString().removeSuffix(".0")} kg" },
                field("warranty")?.let { "Warranty: $it" },
            ).joinToString("\n").ifBlank { null }
            val item = GearItem(
                name = field("name") ?: type ?: "Gear",
                kind = kind,
                brand = field("manufacturer"),
                serialNumber = field("serial"),
                purchasedEpochDay = field("purchase date")?.let(::epochDay),
                purchasePriceCents = field("purchase price")?.toDoubleOrNull()?.takeIf { it > 0 }?.let { (it * 100).roundToLong() },
                shop = field("shop"),
                notes = notes,
            )
            val services = listOfNotNull(field("last service")?.let(::epochDay)?.let { GearService(gearId = UNSAVED_ID, epochDay = it) })
            ImportedGear(item, services)
        }
    }

    private fun epochDay(text: String): Int? = runCatching { LocalDate.parse(text).toEpochDays().toInt() }.getOrNull()

    /**
     * MacDive's gear types, and the names people give their own. MacDive lists a regulator's
     * first stage as "Regulator" and its second stages as "Octopus".
     */
    private fun kindOf(type: String): GearKind = when (type.lowercase()) {
        "regulator", "reg", "first stage", "1st stage" -> GearKind.FIRST_STAGE
        "octopus", "octo", "second stage", "2nd stage", "alternate air source" -> GearKind.SECOND_STAGE
        "bcd", "bc", "wing" -> GearKind.BCD
        "backplate", "harness" -> GearKind.BACKPLATE
        "wetsuit", "shorty" -> GearKind.WETSUIT
        "drysuit", "dry suit" -> GearKind.DRYSUIT
        "undersuit" -> GearKind.UNDERSUIT
        "hood" -> GearKind.HOOD
        "gloves", "dry gloves" -> GearKind.GLOVES
        "boots" -> GearKind.BOOTS
        "computer", "dive computer" -> GearKind.COMPUTER
        "spg", "gauge", "console" -> GearKind.SPG
        "compass" -> GearKind.COMPASS
        "tank", "cylinder" -> GearKind.CYLINDER
        "rebreather", "ccr" -> GearKind.REBREATHER
        "dpv", "scooter" -> GearKind.DPV
        "torch", "light", "lights" -> GearKind.LIGHT
        "mask" -> GearKind.MASK
        "snorkel" -> GearKind.SNORKEL
        "fins" -> GearKind.FINS
        "knife", "cutter" -> GearKind.KNIFE
        "smb", "dsmb", "reel", "spool" -> GearKind.SMB
        "weight", "weights", "weight belt" -> GearKind.WEIGHTS
        "camera", "housing", "strobe" -> GearKind.CAMERA
        else -> GearKind.OTHER
    }

    /** RFC 4180 rows: quoted fields may hold commas, line breaks and doubled quotes; space after a comma is dropped. */
    private fun csvRows(text: String): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var atFieldStart = true
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                quoted && c == '"' -> quoted = false
                quoted -> field.append(c)
                c == '"' && atFieldStart -> { quoted = true; atFieldStart = false }
                c == ' ' && atFieldStart -> {}
                c == ',' -> { row.add(field.toString()); field.clear(); atFieldStart = true }
                c == '\r' -> {}
                c == '\n' -> {
                    row.add(field.toString()); field.clear(); atFieldStart = true
                    rows.add(row); row = mutableListOf()
                }
                else -> { field.append(c); atFieldStart = false }
            }
            i++
        }
        if (quoted) throw FormatException("Not a MacDive gear list: unterminated quote")
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            rows.add(row)
        }
        return rows
    }
}
