package no.synth.divelog.ui.dive

import no.synth.divelog.core.db.DiveRepository
import no.synth.divelog.core.db.MergeProblem

/** What merging does, for the dialogs that ask first. */
internal const val MERGE_EXPLAINED =
    "They become one dive, kept as the earliest with its number, site and notes. A computer's back-to-back " +
        "dives join into one profile, which cannot be split again; computers that logged the same time keep a " +
        "profile each."

/** Why dives cannot be merged, for the user. */
internal fun mergeProblemText(problem: MergeProblem): String = when (problem) {
    MergeProblem.TOO_FEW -> "Pick at least two dives to merge."
    MergeProblem.TOO_FAR_APART ->
        "Only dives at most ${DiveRepository.MAX_SURFACE_INTERVAL_SECONDS / 60} minutes apart can be merged into one."
    MergeProblem.ONE_COMPUTER_AT_ONCE -> "One computer logged two of these dives at the same time, so they cannot be merged."
}
