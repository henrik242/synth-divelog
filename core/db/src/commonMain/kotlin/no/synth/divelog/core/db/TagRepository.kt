package no.synth.divelog.core.db

import kotlinx.coroutines.flow.Flow
import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.Tag

/** Tags and their many-to-many link with dives. */
class TagRepository(private val db: DiveDatabase) {
    private val q = db.tagsQueries

    fun add(name: String): Long = db.transactionWithResult {
        q.insertTag(name)
        q.lastInsertRowId().executeAsOne()
    }

    fun all(): List<Tag> = q.selectAllTags().executeAsList().map { it.toDomain() }

    fun allFlow(): Flow<List<Tag>> = q.selectAllTags().listFlow { it.toDomain() }

    fun get(id: Long): Tag? = q.selectTagById(id).executeAsOneOrNull()?.toDomain()

    fun getFlow(id: Long): Flow<Tag?> = q.selectTagById(id).oneOrNullFlow { it.toDomain() }

    fun rename(id: Long, name: String) = q.updateTag(name, id)

    fun delete(id: Long) = q.deleteTag(id)

    fun linkToDive(diveId: Long, tagId: Long) = q.linkDiveTag(diveId, tagId)

    fun unlinkFromDive(diveId: Long, tagId: Long) = q.unlinkDiveTag(diveId, tagId)

    fun tagsForDive(diveId: Long): List<Tag> =
        q.selectTagsForDive(diveId).executeAsList().map { it.toDomain() }

    fun tagsForDiveFlow(diveId: Long): Flow<List<Tag>> = q.selectTagsForDive(diveId).listFlow { it.toDomain() }

    /** The id of a tag with this name, creating one if it does not exist yet. */
    fun getOrCreate(name: String): Long = db.transactionWithResult {
        q.selectTagByName(name).executeAsOneOrNull()?.id ?: add(name)
    }

    /** Tag names per dive id, for the whole log in one query. */
    fun tagNamesByDive(): Map<Long, List<String>> =
        q.selectAllDiveTagNames().executeAsList().groupBy({ it.diveId }) { it.name }

    fun divesForTag(tagId: Long): List<Dive> {
        val diveIds = q.selectDiveIdsForTag(tagId).executeAsList()
        val diveQueries = db.diveQueries
        return diveIds.mapNotNull { diveQueries.selectDiveById(it).executeAsOneOrNull()?.toDomain() }
            .sortedByDescending { it.startEpochSeconds }
    }
}
