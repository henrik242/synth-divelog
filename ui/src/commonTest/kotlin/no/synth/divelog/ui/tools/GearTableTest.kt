package no.synth.divelog.ui.tools

import kotlin.test.Test
import kotlin.test.assertEquals
import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearKind
import no.synth.divelog.core.model.GearWithLastService

class GearTableTest {
    private val rows: List<GearWithLastService> = listOf(
        GearWithLastService(GearItem(id = 1, name = "MK25", kind = GearKind.FIRST_STAGE, brand = "Scubapro", serialNumber = "0707"), 19_000),
        GearWithLastService(GearItem(id = 2, name = "S600", kind = GearKind.SECOND_STAGE, brand = "Scubapro"), 19_500),
        GearWithLastService(GearItem(id = 3, name = "e. motion", kind = GearKind.DRYSUIT, brand = "Santi", purchasedEpochDay = 16_000), null),
        GearWithLastService(GearItem(id = 5, name = "Vyper", kind = GearKind.COMPUTER, retired = true), null),
        GearWithLastService(GearItem(id = 4, name = "Petrel", kind = GearKind.COMPUTER, brand = "Shearwater", notes = "Bought at Narked"), null),
    )

    private val inUse = rows.filterNot { it.item.retired }

    private fun ids(list: List<GearWithLastService>) = list.map { it.item.id }

    @Test
    fun filtersByEveryWordAndKind() {
        assertEquals(listOf(1L, 2L), ids(filterGear(rows, "scubapro", emptySet())))
        assertEquals(listOf(1L), ids(filterGear(rows, "scubapro 0707", emptySet())))
        assertEquals(listOf(4L), ids(filterGear(rows, "narked", emptySet())))
        assertEquals(listOf(1L), ids(filterGear(rows, "1st stage", emptySet())))
        assertEquals(listOf(2L), ids(filterGear(rows, "", setOf(GearKind.SECOND_STAGE))))
        assertEquals(listOf(1L, 2L), ids(filterGear(rows, "", setOf(GearKind.FIRST_STAGE, GearKind.SECOND_STAGE))))
    }

    @Test
    fun retiredGearOnlyWhenAskedFor() {
        assertEquals(listOf(4L), ids(filterGear(rows, "", setOf(GearKind.COMPUTER))))
        assertEquals(listOf(5L, 4L), ids(filterGear(rows, "", setOf(GearKind.COMPUTER), withRetired = true)))
    }

    @Test
    fun sortsWithMissingValuesLast() {
        assertEquals(listOf(3L, 1L, 4L, 2L), ids(sortGear(inUse, GearColumn.NAME, descending = false)))
        assertEquals(listOf(2L, 1L, 3L, 4L), ids(sortGear(inUse, GearColumn.SERVICED, descending = true)))
        assertEquals(listOf(1L, 2L, 3L, 4L), ids(sortGear(inUse, GearColumn.SERVICED, descending = false)))
        assertEquals(listOf(3L, 1L, 4L, 2L), ids(sortGear(inUse, GearColumn.PURCHASED, descending = false)))
    }
}
