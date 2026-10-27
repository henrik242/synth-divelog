package no.synth.divelog.ui.tools

import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearKind
import kotlin.test.Test
import kotlin.test.assertEquals

class GearTableTest {
    private val rows: List<GearRow> = listOf(
        GearItem(id = 1, name = "MK25", kind = GearKind.FIRST_STAGE, brand = "Scubapro", serialNumber = "0707") to 19_000,
        GearItem(id = 2, name = "S600", kind = GearKind.SECOND_STAGE, brand = "Scubapro") to 19_500,
        GearItem(id = 3, name = "e. motion", kind = GearKind.DRYSUIT, brand = "Santi", purchasedEpochDay = 16_000) to null,
        GearItem(id = 5, name = "Vyper", kind = GearKind.COMPUTER, retired = true) to null,
        GearItem(id = 4, name = "Petrel", kind = GearKind.COMPUTER, brand = "Shearwater", notes = "Bought at Narked") to null,
    )

    private val inUse = rows.filterNot { it.first.retired }

    private fun ids(list: List<GearRow>) = list.map { it.first.id }

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
