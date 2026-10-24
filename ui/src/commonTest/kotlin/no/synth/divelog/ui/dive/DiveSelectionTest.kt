package no.synth.divelog.ui.dive

import androidx.compose.runtime.saveable.SaverScope
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiveSelectionTest {
    private val order = listOf(10L, 20L, 30L, 40L, 50L)

    @Test
    fun toggleAddsThenRemoves() {
        val picked = DiveSelection().toggle(20)
        assertEquals(setOf(20L), picked.ids)
        assertTrue(picked.toggle(20).isEmpty)
    }

    @Test
    fun extendPicksTheRangeFromTheLastToggledDive() {
        val picked = DiveSelection().toggle(20).extendTo(40, order)
        assertEquals(setOf(20L, 30L, 40L), picked.ids)
        // Upwards from the new anchor too.
        assertEquals(setOf(10L, 20L, 30L, 40L), picked.extendTo(10, order).ids)
    }

    @Test
    fun extendWithoutAnAnchorTogglesTheDive() {
        assertEquals(setOf(30L), DiveSelection().extendTo(30, order).ids)
        // An anchor the filter hides counts as none.
        assertEquals(setOf(99L, 30L), DiveSelection().toggle(99).extendTo(30, order).ids)
    }

    @Test
    fun toggleAllPicksTheShownDivesThenDropsThem() {
        val hidden = DiveSelection().toggle(99)
        val all = hidden.toggleAll(order)
        assertEquals(order.toSet() + 99L, all.ids)
        assertTrue(all.allPicked(order))
        // Dropping the shown dives keeps the hidden pick.
        assertEquals(setOf(99L), all.toggleAll(order).ids)
    }

    @Test
    fun countsPickedDivesTheFilterHides() {
        val picked = DiveSelection(setOf(10L, 99L, 98L))
        assertEquals(2, picked.hiddenCount(order))
        assertEquals(0, DiveSelection(setOf(10L)).hiddenCount(order))
    }

    @Test
    fun deletedDivesDropOutAndAreNotPickedAgain() {
        val picked = DiveSelection().toggle(10).toggle(20)
        val pruned = picked.retainExisting(setOf(10L, 30L))
        assertEquals(setOf(10L), pruned.ids)
        assertEquals(null, pruned.anchor)
        assertFalse(20L in pruned.toggle(30))
    }

    @Test
    fun anUnchangedLogbookKeepsTheSameSelection() {
        val picked = DiveSelection().toggle(10)
        assertTrue(picked === picked.retainExisting(order.toSet()))
    }

    @Test
    fun savesAndRestoresTheIds() {
        val picked = DiveSelection().toggle(10).toggle(30)
        val saved = with(DiveSelection.Saver) { SaverScope { true }.save(picked) }
        assertEquals(DiveSelection(setOf(10L, 30L)), DiveSelection.Saver.restore(requireNotNull(saved)))
    }
}
