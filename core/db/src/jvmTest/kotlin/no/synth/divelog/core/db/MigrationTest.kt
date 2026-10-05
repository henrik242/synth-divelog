package no.synth.divelog.core.db

import no.synth.divelog.core.db.sql.DiveDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationTest {
    @Test
    fun schemaVersionBumpedForTags() {
        // Adding the tag migration (1.sqm) takes the schema from version 1 to 2.
        assertEquals(2L, DiveDatabase.Schema.version)
    }

    @Test
    fun freshDatabaseHasTagTables() {
        val tags = TagRepository(testDatabase())
        val id = tags.add("CCR")
        assertTrue(tags.all().any { it.id == id })
    }
}
