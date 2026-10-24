package no.synth.divelog.core.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import no.synth.divelog.core.db.sql.DiveDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationTest {
    @Test
    fun schemaVersionBumpedForDiveSettings() {
        // 1.sqm (tags) takes the schema to version 2, 2.sqm (visibility stars) to 3,
        // 3.sqm (dive settings, time to surface) to 4.
        assertEquals(4L, DiveDatabase.Schema.version)
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
        undo3Sqm(driver)
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

    @Test
    fun version3AddsDiveSettingsAndTimeToSurface() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DiveDatabase.Schema.create(driver)
        undo3Sqm(driver)
        driver.execute(null, "INSERT INTO dive(startEpochSeconds, utcOffsetSeconds, durationSeconds) VALUES (1, 0, 60)", 0)
        driver.execute(
            null,
            "INSERT INTO diveComputerRecord(diveId, startEpochSeconds, durationSeconds, rawData, rawFormatId, fingerprint) " +
                "VALUES (1, 1, 60, x'00', 'f', 'a')",
            0,
        )
        driver.execute(null, "INSERT INTO sample(recordId, timeOffsetSeconds, depthMm) VALUES (1, 0, 5000)", 0)

        DiveDatabase.Schema.migrate(driver, 3, DiveDatabase.Schema.version)

        // An old record and sample have no settings or time to surface, and keep the rest.
        val repo = DiveRepository(DiveDatabase(driver))
        val record = repo.recordsForDive(1).single()
        assertNull(record.diveMode)
        assertNull(record.decoModel)
        val sample = repo.samplesForRecord(record.id).single()
        assertEquals(5_000, sample.depthMm)
        assertNull(sample.ttsSeconds)
    }
}

/** Undo what 3.sqm adds (dive settings on records, time to surface on samples), back to schema version 3. */
internal fun undo3Sqm(driver: JdbcSqliteDriver) {
    for (column in listOf("diveMode", "decoAlgorithm", "gfLowPercent", "gfHighPercent", "decoConservatism")) {
        driver.execute(null, "ALTER TABLE diveComputerRecord DROP COLUMN $column", 0)
    }
    driver.execute(null, "ALTER TABLE sample DROP COLUMN ttsSeconds", 0)
}
