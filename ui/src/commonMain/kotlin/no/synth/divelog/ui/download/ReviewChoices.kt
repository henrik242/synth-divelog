package no.synth.divelog.ui.download

import no.synth.divelog.core.logbook.download.DiveAction
import no.synth.divelog.core.logbook.download.DiveStatus

/**
 * What the download review will do with each dive of [statuses]: whether it is checked,
 * and for a dive that checking merges, whether it is kept apart (imported as a dive of its
 * own) instead. A dive that cannot be taken in ([DiveStatus.checkedAction] is
 * [DiveAction.SKIP]) never gets checked.
 */
data class ReviewChoices(
    private val statuses: List<DiveStatus>,
    private val checked: List<Boolean> = statuses.map { it.default != DiveAction.SKIP },
    private val apart: Set<Int> = emptySet(),
) {
    val actions: List<DiveAction> get() = statuses.indices.map(::action)

    fun action(i: Int): DiveAction = when {
        !checked[i] -> DiveAction.SKIP
        canKeepApart(i) && i in apart -> DiveAction.IMPORT
        else -> statuses[i].checkedAction
    }

    fun isChecked(i: Int): Boolean = checked[i]

    fun canCheck(i: Int): Boolean = statuses[i].checkedAction != DiveAction.SKIP

    fun canKeepApart(i: Int): Boolean = statuses[i].checkedAction == DiveAction.MERGE

    fun toggle(i: Int): ReviewChoices =
        if (canCheck(i)) copy(checked = checked.mapIndexed { j, c -> if (j == i) !c else c }) else this

    fun keepApart(i: Int, keep: Boolean): ReviewChoices = copy(apart = if (keep) apart + i else apart - i)

    /** What the review starts with checked; a dive kept apart stays apart. */
    fun suggested(): ReviewChoices = copy(checked = statuses.map { it.default != DiveAction.SKIP })

    fun all(): ReviewChoices = copy(checked = statuses.indices.map(::canCheck))

    fun none(): ReviewChoices = copy(checked = statuses.map { false })
}
