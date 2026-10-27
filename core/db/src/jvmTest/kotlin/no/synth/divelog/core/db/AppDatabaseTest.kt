package no.synth.divelog.core.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.model.Site
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppDatabaseTest {
    private fun <T> withFile(block: (path: String) -> T): T {
        val file = File.createTempFile("app-db-test", ".db").also { it.delete() }
        try {
            return block(file.path)
        } finally {
            file.delete()
        }
    }

    private fun AppDatabase.counts(): Map<String, Int> {
        val dives = DiveRepository(this)
        return mapOf(
            "dives" to dives.allDives().size,
            "records" to dives.recordsByDive().values.sumOf { it.size },
            "samples" to dives.samplesByRecord().values.sumOf { it.size },
        )
    }

    @Test
    fun aFileDatabaseOnTheDesktopEnforcesForeignKeys() = withFile { path ->
        val db = createDatabase(path)
        val dives = DiveRepository(db)
        val diveId = dives.importAsNewDive(incoming(samples = listOf(sampleAt(0, 0), sampleAt(10, 5_000)))).diveId

        dives.deleteDive(diveId)

        // Each statement runs on a connection of its own, so this holds only if every connection has them on.
        assertEquals(mapOf("dives" to 0, "records" to 0, "samples" to 0), db.counts())
    }

    @Test
    fun repairGivesRowsWithoutAParentWhatTheirForeignKeySays() {
        // No foreign keys, as on the desktop before they were enforced.
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DiveDatabase.Schema.create(it) }
        val db = AppDatabase(driver)
        val dives = DiveRepository(db)
        val sites = SiteRepository(db)
        val gone = dives.importAsNewDive(incoming(start = 1_000, samples = listOf(sampleAt(0, 0)))).diveId
        val kept = dives.importAsNewDive(incoming(start = 900_000, samples = listOf(sampleAt(0, 0)))).diveId
        val siteId = sites.addSite(Site(name = "The Wall", placeId = sites.getOrCreatePlace("Norway", "Drøbak")))
        dives.setSite(kept, siteId)
        driver.execute(null, "DELETE FROM dive WHERE id = $gone", 0)
        driver.execute(null, "DELETE FROM site WHERE id = $siteId", 0)

        // The record (ON DELETE CASCADE), then its sample in the next round, and the site (SET NULL).
        assertEquals(3, db.repairForeignKeys())

        assertEquals(mapOf("dives" to 1, "records" to 1, "samples" to 1), db.counts())
        assertNull(dives.getDive(kept)?.siteId)
        assertEquals(0, db.repairForeignKeys())
    }

    @Test
    fun replacedRowsThatDoNotFitRollBackAndLeaveTheDatabaseUsable() = withFile { path ->
        val db = createDatabase(path)
        val buddies = BuddyRepository(db)
        buddies.add("Alex")

        assertFailsWith<IllegalStateException> {
            db.replaceAllRows { execute -> execute("INSERT INTO diveBuddy(diveId, buddyId) VALUES (99, 99)") }
        }

        assertEquals(listOf("Alex"), buddies.all().map { it.name })
        buddies.add("Sam")
        assertEquals(listOf("Alex", "Sam"), buddies.all().map { it.name }.sorted())
        assertEquals(listOf("Alex", "Sam"), BuddyRepository(createDatabase(path)).all().map { it.name }.sorted())
    }

    @Test
    fun migratesFromEveryOlderVersionThroughPlainStatements() {
        val current = createDatabase().tableColumns().mapValues { it.value.toSet() }
        for (version in 2L until DiveDatabase.Schema.version) {
            val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DiveDatabase.Schema.create(it) }
            when (version) {
                2L -> {
                    driver.execute(null, "ALTER TABLE dive DROP COLUMN visibilityRating", 0)
                    undo3Sqm(driver)
                }
                3L -> undo3Sqm(driver)
                4L -> undo4Sqm(driver)
                5L -> undo5Sqm(driver)
                6L -> undo6Sqm(driver)
                7L -> undo7Sqm(driver)
                else -> error("No way back to version $version; add one to MigrationTest")
            }

            AppDatabase.migrate(version) { driver.execute(null, it, 0) }

            assertEquals(current, AppDatabase(driver).tableColumns().mapValues { it.value.toSet() }, "from version $version")
        }
        assertTrue(DiveDatabase.Schema.version > 2)
    }
}
