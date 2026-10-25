package no.synth.divelog.core.db

/**
 * What should happen to an incoming record, decided before any write so the UI
 * can preview it. Merging always needs user confirmation.
 */
sealed interface ImportDecision {
    /** Same device and fingerprint already stored; skip. */
    data class Duplicate(val existingRecordId: Long) : ImportDecision

    /** Time range overlaps an existing dive; offer to attach as another computer. */
    data class MergeCandidate(val diveId: Long) : ImportDecision

    /** No duplicate and no overlap; import as a brand new dive. */
    data object NewDive : ImportDecision
}

/** The outcome of a committed import. */
sealed interface ImportResult {
    data class SkippedDuplicate(val existingRecordId: Long) : ImportResult

    data class CreatedDive(val diveId: Long, val recordId: Long) : ImportResult

    data class AttachedToDive(val diveId: Long, val recordId: Long) : ImportResult
}

/** Why dives cannot be merged into one ([DiveRepository.mergeProblem]). */
enum class MergeProblem {
    /** Fewer than two dives. */
    TOO_FEW,

    /** A surface interval between them is longer than [DiveRepository.MAX_SURFACE_INTERVAL_SECONDS]. */
    TOO_FAR_APART,

    /** One computer logged two of them at the same time. */
    ONE_COMPUTER_AT_ONCE,
}
