package no.synth.divelog.ui.tools

import kotlinx.datetime.LocalDate
import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class GearDraftTest {
    private val reg = GearItem(
        id = 1,
        name = "Reg",
        kind = GearKind.FIRST_STAGE,
        purchasedEpochDay = epochDay(LocalDate(2025, 3, 31)),
        purchasePriceCents = 170_050,
        shop = "Dive shop",
    )

    @Test
    fun unchangedDraftKeepsTheItem() {
        assertEquals(reg, GearDraft.of(reg).applyTo(reg))
        assertEquals("2025-03-31", GearDraft.of(reg).purchased)
        assertEquals("1700.50", GearDraft.of(reg).price)
        assertEquals(150_000, GearDraft.of(reg).copy(price = "1500").applyTo(reg).purchasePriceCents)
        assertEquals(150_025, GearDraft.of(reg).copy(price = "1500,25").applyTo(reg).purchasePriceCents)
    }

    @Test
    fun blanksClearAndInvalidEntriesKeep() {
        val draft = GearDraft.of(reg)
        assertNull(draft.copy(purchased = "", price = "").applyTo(reg).purchasedEpochDay)
        assertEquals(reg, draft.copy(name = " ", purchased = "2025-13", price = "x").applyTo(reg))
        assertFalse(draft.copy(price = "x").priceValid())
        assertEquals("Mk25", draft.copy(model = " Mk25 ").applyTo(reg).model)
    }

    @Test
    fun timeSinceInYearsMonthsOrDays() {
        val today = LocalDate(2026, 10, 26)
        assertEquals("3 years 1 month ago", timeSince(LocalDate(2023, 9, 17), today))
        assertEquals("2 years ago", timeSince(LocalDate(2024, 10, 1), today))
        assertEquals("5 months ago", timeSince(LocalDate(2026, 5, 20), today))
        assertEquals("1 day ago", timeSince(LocalDate(2026, 10, 25), today))
        assertEquals("today", timeSince(today, today))
        assertEquals("in the future", timeSince(LocalDate(2026, 11, 1), today))
    }

    @Test
    fun shortTimeSinceForTheTable() {
        val today = LocalDate(2026, 10, 26)
        assertEquals("3y 1m", shortTimeSince(LocalDate(2023, 9, 17), today))
        assertEquals("2y", shortTimeSince(LocalDate(2024, 10, 1), today))
        assertEquals("5m", shortTimeSince(LocalDate(2026, 5, 20), today))
        assertEquals("1d", shortTimeSince(LocalDate(2026, 10, 25), today))
        assertEquals("today", shortTimeSince(today, today))
    }
}
