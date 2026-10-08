package no.synth.divelog.core.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import no.synth.divelog.core.db.sql.DiveDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationTest {
    @Test
    fun schemaVersionBumpedForVisibilityStars() {
        // 1.sqm (tags) takes the schema to version 2, 2.sqm (visibility stars) to 3.
        assertEquals(3L, DiveDatabase.Schema.version)
    }

    @Test
    fun freshDatabaseHasTagTables() {
        val tags = TagRepository(testDatabase())
        val id = tags.add("CCR")
        assertTrue(tags.all().any { it.id == id })
    }

    @Test
    fun version2MovesVisibilityStarsToTheirOwnColumn() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DiveDatabase.Schema.create(driver)
        // Back to the version-2 dive table: no visibilityRating column.
        driver.execute(null, "ALTER TABLE dive DROP COLUMN visibilityRating", 0)
        driver.execute(null, "INSERT INTO dive(startEpochSeconds, utcOffsetSeconds, durationSeconds, visibility) VALUES (1, 0, 60, 3)", 0)
        driver.execute(null, "INSERT INTO dive(startEpochSeconds, utcOffsetSeconds, durationSeconds, visibility) VALUES (2, 0, 60, 12000)", 0)

        DiveDatabase.Schema.migrate(driver, 2, DiveDatabase.Schema.version)

        val byStart = DiveRepository(DiveDatabase(driver)).allDives().associateBy { it.startEpochSeconds }
        val stars = byStart.getValue(1)
        assertNull(stars.visibility)
        assertEquals(3, stars.visibilityRating)
        val distance = byStart.getValue(2)
        assertEquals(12_000, distance.visibility)
        assertNull(distance.visibilityRating)
    }
}
