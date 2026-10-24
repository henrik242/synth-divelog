package no.synth.divelog.ui.dive

import androidx.compose.runtime.saveable.Saver

/**
 * Dives picked in the dive list for export: [ids], and the [anchor] a range pick extends
 * from (the dive last toggled). Hidden dives stay picked while a filter hides them.
 */
data class DiveSelection(val ids: Set<Long> = emptySet(), val anchor: Long? = null) {
    val isEmpty: Boolean get() = ids.isEmpty()

    operator fun contains(id: Long): Boolean = id in ids

    fun toggle(id: Long): DiveSelection = DiveSelection(if (id in ids) ids - id else ids + id, anchor = id)

    /** Adds the dives from [anchor] through [id], in list [order]; without an anchor in [order], toggles [id]. */
    fun extendTo(id: Long, order: List<Long>): DiveSelection {
        val from = anchor?.let(order::indexOf)?.takeIf { it >= 0 } ?: return toggle(id)
        val to = order.indexOf(id).takeIf { it >= 0 } ?: return toggle(id)
        return DiveSelection(ids + order.subList(minOf(from, to), maxOf(from, to) + 1), anchor = id)
    }

    /** All of [shown] picked, or none of them when they all were already. */
    fun toggleAll(shown: List<Long>): DiveSelection =
        if (allPicked(shown)) copy(ids = ids - shown.toSet()) else copy(ids = ids + shown)

    fun allPicked(shown: List<Long>): Boolean = shown.isNotEmpty() && ids.containsAll(shown)

    /** Picked dives not in [shown]. */
    fun hiddenCount(shown: Collection<Long>): Int = (ids - shown.toSet()).size

    /** Drops dives that no longer exist. */
    fun retainExisting(existing: Set<Long>): DiveSelection =
        if (existing.containsAll(ids)) this else DiveSelection(ids intersect existing, anchor?.takeIf { it in existing })

    companion object {
        /** Keeps the ids (a range pick starts afresh after a restore). */
        val Saver: Saver<DiveSelection, LongArray> = Saver(
            save = { it.ids.toLongArray() },
            restore = { DiveSelection(it.toSet()) },
        )
    }
}
