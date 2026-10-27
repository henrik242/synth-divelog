package no.synth.divelog.ui.tools

import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearKind

/** A piece of gear and the day of its latest service, as the gear list shows it. */
internal typealias GearRow = Pair<GearItem, Int?>

/** The gear list's columns, in the order shown; a narrow screen shows only some. */
internal enum class GearColumn(val label: String, val weight: Float) {
    NAME("Name", 2f),
    KIND("Kind", 1.4f),
    BRAND("Brand / model", 1.6f),
    SERIAL("Serial", 1.2f),
    PURCHASED("Purchased", 1f),
    SERVICED("Last service", 1.4f),
}

/**
 * The rows whose text holds every word of [query] and that are of one of [kinds] (any kind
 * when empty); retired gear only [withRetired].
 */
internal fun filterGear(rows: List<GearRow>, query: String, kinds: Set<GearKind>, withRetired: Boolean = false): List<GearRow> {
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    return rows.filter { (item, _) ->
        val text = listOfNotNull(item.name, item.kind?.label, item.brand, item.model, item.serialNumber, item.shop, item.notes)
            .joinToString(" ").lowercase()
        (withRetired || !item.retired) && (kinds.isEmpty() || item.kind in kinds) && words.all { it in text }
    }
}

/** [rows] by [column], [descending] or not; rows without a value go last either way, then by name. */
internal fun sortGear(rows: List<GearRow>, column: GearColumn, descending: Boolean): List<GearRow> {
    val byName = compareBy<GearRow> { it.first.name.lowercase() }
    val key: (GearRow) -> Comparable<*>? = when (column) {
        GearColumn.NAME -> { row -> row.first.name.lowercase() }
        GearColumn.KIND -> { row -> row.first.kind?.ordinal }
        GearColumn.BRAND -> { row -> brandModel(row.first)?.lowercase() }
        GearColumn.SERIAL -> { row -> row.first.serialNumber?.lowercase() }
        GearColumn.PURCHASED -> { row -> row.first.purchasedEpochDay }
        GearColumn.SERVICED -> { row -> row.second }
    }
    val present = rows.filter { key(it) != null }
    val missing = rows.filter { key(it) == null }
    val byKey = compareBy(key)
    return present.sortedWith((if (descending) byKey.reversed() else byKey).then(byName)) + missing.sortedWith(byName)
}

internal fun brandModel(item: GearItem): String? = listOfNotNull(item.brand, item.model).joinToString(" ").ifBlank { null }
