package no.synth.divelog.core.db

import kotlinx.coroutines.flow.Flow
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

    fun allFlow(): Flow<List<Buddy>> = q.selectAllBuddies().listFlow { it.toDomain() }

    fun get(id: Long): Buddy? = q.selectBuddyById(id).executeAsOneOrNull()?.toDomain()

    fun getFlow(id: Long): Flow<Buddy?> = q.selectBuddyById(id).oneOrNullFlow { it.toDomain() }

    /** The id of a buddy with this name, creating one if there is none yet. */
    fun getOrCreate(name: String): Long = db.transactionWithResult {
        q.selectBuddyByName(name).executeAsOneOrNull()?.id ?: add(name)
    }

    fun rename(id: Long, name: String) = q.updateBuddy(name, id)

    fun delete(id: Long) = q.deleteBuddy(id)

    fun linkToDive(diveId: Long, buddyId: Long) = q.linkDiveBuddy(diveId, buddyId)

    fun unlinkFromDive(diveId: Long, buddyId: Long) = q.unlinkDiveBuddy(diveId, buddyId)

    fun buddiesForDive(diveId: Long): List<Buddy> =
        q.selectBuddiesForDive(diveId).executeAsList().map { it.toDomain() }

    fun buddiesForDiveFlow(diveId: Long): Flow<List<Buddy>> = q.selectBuddiesForDive(diveId).listFlow { it.toDomain() }

    /** Buddy names per dive id, for the whole log in one query. */
    fun buddyNamesByDive(): Map<Long, List<String>> =
        q.selectAllDiveBuddyNames().executeAsList().groupBy({ it.diveId }) { it.name }

    fun divesForBuddy(buddyId: Long): List<Dive> {
        val diveIds = q.selectDiveIdsForBuddy(buddyId).executeAsList()
        val diveQueries = db.diveQueries
        return diveIds.mapNotNull { diveQueries.selectDiveById(it).executeAsOneOrNull()?.toDomain() }
            .sortedByDescending { it.startEpochSeconds }
    }
}
