package no.synth.divelog.core.db

import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.model.Buddy
import no.synth.divelog.core.model.Dive

/** Buddies and their many-to-many link with dives. */
class BuddyRepository(private val db: DiveDatabase) {
    private val q = db.peopleQueries

    fun add(name: String): Long = db.transactionWithResult {
        q.insertBuddy(name)
        q.lastInsertRowId().executeAsOne()
    }

    fun all(): List<Buddy> = q.selectAllBuddies().executeAsList().map { it.toDomain() }

    fun get(id: Long): Buddy? = q.selectBuddyById(id).executeAsOneOrNull()?.toDomain()

    fun rename(id: Long, name: String) = q.updateBuddy(name, id)

    fun delete(id: Long) = q.deleteBuddy(id)

    fun linkToDive(diveId: Long, buddyId: Long) = q.linkDiveBuddy(diveId, buddyId)

    fun unlinkFromDive(diveId: Long, buddyId: Long) = q.unlinkDiveBuddy(diveId, buddyId)

    fun buddiesForDive(diveId: Long): List<Buddy> =
        q.selectBuddiesForDive(diveId).executeAsList().map { it.toDomain() }

    fun divesForBuddy(buddyId: Long): List<Dive> {
        val diveIds = q.selectDiveIdsForBuddy(buddyId).executeAsList()
        val diveQueries = db.diveQueries
        return diveIds.mapNotNull { diveQueries.selectDiveById(it).executeAsOneOrNull()?.toDomain() }
            .sortedByDescending { it.startEpochSeconds }
    }
}
