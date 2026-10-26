package no.synth.divelog.ui.dive

import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.units.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DiveSummaryDraftTest {
    // 2026-10-26 09:30 on a UTC+1 wall clock.
    private val dive = Dive(
        startEpochSeconds = 1_793_007_000 - 3_600,
        utcOffsetSeconds = 3_600,
        durationSeconds = 2_730,
        maxDepthMm = 18_300,
        waterTempMk = 285_150,
    )

    @Test
    fun showsTheWallClockAndUnits() {
        assertEquals(
            DiveSummaryDraft("2026-10-26", "09:30", "45:30", "18.3", "", "12"),
            DiveSummaryDraft.of(dive, UnitSystem.METRIC),
        )
        assertEquals("60", DiveSummaryDraft.of(dive, UnitSystem.IMPERIAL).maxDepth)
    }

    @Test
    fun unchangedDraftKeepsTheDive() {
        UnitSystem.entries.forEach { system ->
            assertEquals(dive, DiveSummaryDraft.of(dive, system).applyTo(dive, system), "$system")
        }
    }

    @Test
    fun appliesTypedValues() {
        val draft = DiveSummaryDraft("2026-10-27", "14:05", "52", "30", "15.5", "")
        val applied = draft.applyTo(dive, UnitSystem.METRIC)
        assertEquals(dive.startEpochSeconds + 86_400 + 4 * 3_600 + 35 * 60, applied.startEpochSeconds)
        assertEquals(3_600, applied.utcOffsetSeconds)
        assertEquals(52 * 60, applied.durationSeconds)
        assertEquals(30_000, applied.maxDepthMm)
        assertEquals(15_500, applied.meanDepthMm)
        assertEquals(null, applied.waterTempMk)
    }

    @Test
    fun imperialInputConverts() {
        val draft = DiveSummaryDraft.of(dive, UnitSystem.IMPERIAL).copy(maxDepth = "100", waterTemp = "50")
        val applied = draft.applyTo(dive, UnitSystem.IMPERIAL)
        assertEquals(30_480, applied.maxDepthMm)
        assertEquals(283_150, applied.waterTempMk)
    }

    @Test
    fun belowZeroWater() {
        val applied = DiveSummaryDraft.of(dive, UnitSystem.METRIC).copy(waterTemp = "-1.5").applyTo(dive, UnitSystem.METRIC)
        assertEquals(271_650, applied.waterTempMk)
        assertEquals("-1.5", DiveSummaryDraft.of(applied, UnitSystem.METRIC).waterTemp)
    }

    @Test
    fun pickedDateRoundTrips() {
        val draft = DiveSummaryDraft.of(dive, UnitSystem.METRIC)
        val millis = requireNotNull(draft.dateMillis())
        assertEquals(1_793_007_000_000 - (9 * 3_600 + 30 * 60) * 1_000L, millis)
        assertEquals("2026-10-30", draft.withDateMillis(millis + 4 * 86_400_000L).date)
    }

    @Test
    fun pickedTime() {
        val draft = DiveSummaryDraft.of(dive, UnitSystem.METRIC)
        assertEquals(9 to 30, requireNotNull(draft.timeOfDay()).let { it.hour to it.minute })
        assertEquals("07:05", draft.withTime(7, 5).time)
    }

    @Test
    fun anUnchangedTimeKeepsItsSeconds() {
        val withSeconds = dive.copy(startEpochSeconds = dive.startEpochSeconds + 17)
        val draft = DiveSummaryDraft.of(withSeconds, UnitSystem.METRIC).copy(duration = "50")
        assertEquals(withSeconds.startEpochSeconds, draft.applyTo(withSeconds, UnitSystem.METRIC).startEpochSeconds)
    }

    @Test
    fun datesOutsideThePickerAreInvalid() {
        val draft = DiveSummaryDraft.of(dive, UnitSystem.METRIC)
        assertFalse(draft.copy(date = "1800-01-01").dateValid())
        assertFalse(draft.copy(date = "2101-01-01").dateValid())
        assertNull(draft.copy(date = "1800-01-01").dateMillis())
    }

    @Test
    fun invalidFieldsKeepTheDive() {
        val draft = DiveSummaryDraft("2026-13-01", "25:00", "4:5", "x", "", "12")
        assertFalse(draft.dateValid())
        assertFalse(draft.timeValid())
        assertFalse(draft.durationValid())
        assertFalse(draft.maxDepthValid())
        val applied = draft.applyTo(dive, UnitSystem.METRIC)
        assertEquals(dive.startEpochSeconds, applied.startEpochSeconds)
        assertEquals(dive.durationSeconds, applied.durationSeconds)
        assertEquals(dive.maxDepthMm, applied.maxDepthMm)
    }
}
