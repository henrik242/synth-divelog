// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

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
