package no.synth.divelog.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import no.synth.divelog.core.db.sql.DiveDatabase
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DatabaseMigrationTest {
    private val file = File.createTempFile("divelog", ".db")

    @AfterTest
    fun cleanUp() {
        file.delete()
    }

    @Test
    fun migratesVersionOneDatabase() {
        // Version 1 is today's schema without what 1.sqm (tag tables) and 2.sqm (visibility stars) add.
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            DiveDatabase.Schema.create(driver)
            driver.execute(null, "DROP TABLE diveTag", 0)
            driver.execute(null, "DROP TABLE tag", 0)
            driver.execute(null, "ALTER TABLE dive DROP COLUMN visibilityRating", 0)
            driver.execute(null, "PRAGMA user_version = 1", 0)
        }

        val db = createDatabase(file.absolutePath)

        val tags = TagRepository(db)
        tags.getOrCreate("wreck")
        assertEquals(listOf("wreck"), tags.all().map { it.name })
        assertEquals(DiveDatabase.Schema.version, userVersion())
    }

    @Test
    fun createsSchemaInEmptyFile() {
        // The file exists but is empty, as after an interrupted first run.
        val db = createDatabase(file.absolutePath)

        TagRepository(db).getOrCreate("night")
        assertEquals(1, TagRepository(db).all().size)
        assertEquals(DiveDatabase.Schema.version, userVersion())
    }

    @Test
    fun reopensCurrentDatabase() {
        TagRepository(createDatabase(file.absolutePath)).getOrCreate("reef")

        assertEquals(listOf("reef"), TagRepository(createDatabase(file.absolutePath)).all().map { it.name })
    }

    private fun userVersion(): Long =
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            driver.executeQuery(
                identifier = null,
                sql = "PRAGMA user_version",
                mapper = { cursor ->
                    QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
                },
                parameters = 0,
            ).value
        }
}
