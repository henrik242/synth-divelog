package no.synth.divelog.core.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationTest {
    @Test
    fun schemaVersionBumpedForGear() {
        // 1.sqm (tags) takes the schema to version 2, 2.sqm (visibility stars) to 3,
        // 3.sqm (dive settings, time to surface) to 4, 4.sqm (deleted dives) to 5, 5.sqm (gear) to 6.
        assertEquals(6L, DiveDatabase.Schema.version)
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
    fun version4RemembersDeletedDivesFromThenOn() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DiveDatabase.Schema.create(driver)
        undo4Sqm(driver)
        driver.execute(null, "INSERT INTO device(vendor, model) VALUES ('Shearwater', 'Petrel')", 0)

        DiveDatabase.Schema.migrate(driver, 4, DiveDatabase.Schema.version)

        val repo = DiveRepository(DiveDatabase(driver))
        val diveId = repo.importAsNewDive(incoming(deviceId = 1, fingerprint = "a")).diveId
        assertFalse(repo.isDeleted(1, "a"))
        repo.deleteDive(diveId)
        assertTrue(repo.isDeleted(1, "a"))
    }

    @Test
    fun version5AddsGear() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DiveDatabase.Schema.create(driver)
        undo5Sqm(driver)

        DiveDatabase.Schema.migrate(driver, 5, DiveDatabase.Schema.version)

        val gear = GearRepository(DiveDatabase(driver))
        val id = gear.add(GearItem(name = "Regulator"))
        gear.addService(GearService(gearId = id, epochDay = 20_000))
        assertEquals(listOf(20_000), gear.services(id).map { it.epochDay })
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

/**
 * Undo what 3.sqm adds (dive settings on records, time to surface on samples) and what
 * later ones add ([undo4Sqm]), back to schema version 3.
 */
internal fun undo3Sqm(driver: JdbcSqliteDriver) {
    undo4Sqm(driver)
    for (column in listOf("diveMode", "decoAlgorithm", "gfLowPercent", "gfHighPercent", "decoConservatism")) {
        driver.execute(null, "ALTER TABLE diveComputerRecord DROP COLUMN $column", 0)
    }
    driver.execute(null, "ALTER TABLE sample DROP COLUMN ttsSeconds", 0)
}

/** Undo what 4.sqm adds (deleted dives) and what 5.sqm adds ([undo5Sqm]), back to schema version 4. */
internal fun undo4Sqm(driver: JdbcSqliteDriver) {
    undo5Sqm(driver)
    driver.execute(null, "DROP TABLE deletedRecord", 0)
}

/** Undo what 5.sqm adds (gear), back to schema version 5. */
internal fun undo5Sqm(driver: JdbcSqliteDriver) {
    driver.execute(null, "DROP TABLE gearService", 0)
    driver.execute(null, "DROP TABLE gear", 0)
}
