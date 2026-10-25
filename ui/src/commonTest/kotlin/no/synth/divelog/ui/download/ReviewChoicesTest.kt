package no.synth.divelog.ui.download

import no.synth.divelog.core.logbook.download.DiveAction
import no.synth.divelog.core.logbook.download.DiveRef
import no.synth.divelog.core.logbook.download.DiveStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class ReviewChoicesTest {
    private val ref = DiveRef(number = 7, startEpochSeconds = 0, utcOffsetSeconds = 0)
    private val statuses = listOf(
        DiveStatus.New,
        DiveStatus.Overlaps(ref, computer = null),
        DiveStatus.Deleted(),
        DiveStatus.Stored(ref, secondComputer = false),
        DiveStatus.Stored(ref, secondComputer = false, joined = true),
    )

    @Test
    fun newDivesAndOverlapsStartChecked() {
        assertEquals(
            listOf(DiveAction.IMPORT, DiveAction.MERGE, DiveAction.SKIP, DiveAction.SKIP, DiveAction.SKIP),
            ReviewChoices(statuses).actions,
        )
    }

    @Test
    fun selectAllTakesInWhatCanBeTakenIn() {
        assertEquals(
            listOf(DiveAction.IMPORT, DiveAction.MERGE, DiveAction.IMPORT, DiveAction.REFRESH, DiveAction.SKIP),
            ReviewChoices(statuses).all().actions,
        )
    }

    @Test
    fun aJoinedDiveCannotBeChecked() {
        assertEquals(DiveAction.SKIP, ReviewChoices(statuses).toggle(4).action(4))
    }

    @Test
    fun anOverlapKeptApartStaysApartThroughToggles() {
        val apart = ReviewChoices(statuses).keepApart(1, true)
        assertEquals(DiveAction.IMPORT, apart.action(1))
        assertEquals(DiveAction.SKIP, apart.toggle(1).action(1))
        assertEquals(DiveAction.IMPORT, apart.toggle(1).toggle(1).action(1))
        assertEquals(DiveAction.IMPORT, apart.none().suggested().action(1))
    }

    @Test
    fun aDeletedDiveThatOverlapsMergesWhenTakenBack() {
        val choices = ReviewChoices(listOf(DiveStatus.Deleted(overlaps = ref))).toggle(0)
        assertEquals(DiveAction.MERGE, choices.action(0))
        assertEquals(DiveAction.IMPORT, choices.keepApart(0, true).action(0))
    }
}
