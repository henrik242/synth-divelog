package no.synth.divelog.core.db

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearKind
import no.synth.divelog.core.model.GearService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MigrationTest {
    @Test
    fun schemaVersionBumpedForComputerGear() {
        // 1.sqm (tags) takes the schema to version 2, 2.sqm (visibility stars) to 3,
        // 3.sqm (dive settings, time to surface) to 4, 4.sqm (deleted dives) to 5, 5.sqm (gear) to 6,
        // 6.sqm (gear on dives) to 7, 7.sqm (computers as gear) to 8.
        assertEquals(8L, DiveDatabase.Schema.version)
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
    fun version6AddsGearToDives() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DiveDatabase.Schema.create(driver)
        undo6Sqm(driver)
        driver.execute(null, "INSERT INTO device(vendor, model) VALUES ('Shearwater', 'Petrel')", 0)

        DiveDatabase.Schema.migrate(driver, 6, DiveDatabase.Schema.version)

        val db = DiveDatabase(driver)
        val diveId = DiveRepository(db).importAsNewDive(incoming(deviceId = 1, fingerprint = "a")).diveId
        val gear = GearRepository(db)
        val id = gear.add(GearItem(name = "Wing"))
        gear.linkToDive(diveId, id)
        assertEquals(listOf("Wing"), gear.gearForDive(diveId).map { it.name })
    }

    @Test
    fun version7MakesComputersGear() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DiveDatabase.Schema.create(driver)
        undo7Sqm(driver)
        driver.execute(null, "INSERT INTO device(vendor, model, serial, nickname) VALUES ('Shearwater', 'Perdix', 'ABC', NULL)", 0)
        driver.execute(null, "INSERT INTO device(vendor, model, serial, nickname) VALUES ('Suunto', 'Vyper', NULL, 'Old yellow')", 0)
        driver.execute(null, "INSERT INTO gear(name, kind, serialNumber) VALUES ('My Perdix', 'COMPUTER', 'ABC')", 0)
        driver.execute(null, "INSERT INTO gear(name, kind) VALUES ('Fins', 'FINS')", 0)

        DiveDatabase.Schema.migrate(driver, 7, DiveDatabase.Schema.version)

        val db = DiveDatabase(driver)
        val gear = GearRepository(db)
        val devices = DeviceRepository(db)
        // The Perdix takes the gear item with its serial; the Vyper gets one named by its nickname.
        assertEquals(1L, gear.forDevice(1)?.id)
        assertEquals("Old yellow", gear.forDevice(2)?.name)
        assertEquals(GearKind.COMPUTER, gear.forDevice(2)?.kind)
        assertEquals(3, gear.all().size)
        assertEquals("My Perdix", devices.get(1)?.nickname)
    }

    @Test
    fun version7LinksBySerialOnlyWithinTheMaker() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DiveDatabase.Schema.create(driver)
        undo7Sqm(driver)
        driver.execute(null, "INSERT INTO device(vendor, model, serial) VALUES ('Suunto', 'Zoop', '123')", 0)
        driver.execute(null, "INSERT INTO device(vendor, model, serial) VALUES ('Shearwater', 'Perdix', 'ABC')", 0)
        driver.execute(null, "INSERT INTO device(vendor, model, serial) VALUES ('Mares', 'Puck', '7')", 0)
        driver.execute(null, "INSERT INTO device(vendor, model, serial) VALUES ('Mares', 'Quad', '7')", 0)
        driver.execute(null, "INSERT INTO gear(name, kind, brand, serialNumber) VALUES ('Puck', 'COMPUTER', 'Mares', '123')", 0)
        driver.execute(null, "INSERT INTO gear(name, kind, brand, serialNumber) VALUES ('Perdix', 'COMPUTER', 'Shearwater Research', 'ABC')", 0)
        driver.execute(null, "INSERT INTO gear(name, kind, brand, serialNumber) VALUES ('Which', 'COMPUTER', 'Mares', '7')", 0)

        DiveDatabase.Schema.migrate(driver, 7, DiveDatabase.Schema.version)

        val db = DiveDatabase(driver)
        val gear = GearRepository(db)
        // Another maker's serial does not link; MacDive's longer maker name does; two Mares with one serial link neither.
        assertEquals("Suunto Zoop", gear.forDevice(1)?.name)
        assertEquals(2L, gear.forDevice(2)?.id)
        assertEquals("Mares Puck", gear.forDevice(3)?.name)
        assertEquals("Mares Quad", gear.forDevice(4)?.name)
        assertEquals(listOf("Puck", "Which"), gear.all().filter { it.deviceId == null }.map { it.name })
    }

    @Test
    fun version7KeepsTheUsersNicknameAndNamesBlankComputers() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        DiveDatabase.Schema.create(driver)
        undo7Sqm(driver)
        driver.execute(null, "INSERT INTO device(vendor, model, serial, nickname) VALUES ('Shearwater', 'Perdix', 'ABC', 'Blue')", 0)
        driver.execute(null, "INSERT INTO device(vendor, model) VALUES ('', '')", 0)
        driver.execute(null, "INSERT INTO gear(name, kind, serialNumber) VALUES ('My Perdix', 'COMPUTER', 'ABC')", 0)

        DiveDatabase.Schema.migrate(driver, 7, DiveDatabase.Schema.version)

        val db = DiveDatabase(driver)
        val gear = GearRepository(db)
        assertEquals("Blue", gear.forDevice(1)?.name)
        assertEquals("Blue", DeviceRepository(db).get(1)?.nickname)
        assertEquals("Dive computer", gear.forDevice(2)?.name)
        assertEquals(null, gear.forDevice(2)?.brand)
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

/** Undo what 5.sqm adds (gear) and what 6.sqm adds ([undo6Sqm]), back to schema version 5. */
internal fun undo5Sqm(driver: JdbcSqliteDriver) {
    undo6Sqm(driver)
    driver.execute(null, "DROP TABLE gearService", 0)
    driver.execute(null, "DROP TABLE gear", 0)
}

/** Undo what 6.sqm adds (gear on dives) and what 7.sqm adds ([undo7Sqm]), back to schema version 6. */
internal fun undo6Sqm(driver: JdbcSqliteDriver) {
    undo7Sqm(driver)
    driver.execute(null, "DROP TABLE diveGear", 0)
}

/** Undo what 7.sqm adds (computers as gear), back to schema version 7. SQLite cannot drop a column with a foreign key, so the table is rebuilt. */
internal fun undo7Sqm(driver: JdbcSqliteDriver) {
    driver.execute(null, "DROP INDEX gear_device", 0)
    driver.execute(
        null,
        """
        CREATE TABLE gear_v7 (
            id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, kind TEXT, brand TEXT, model TEXT,
            serialNumber TEXT, purchasedEpochDay INTEGER, purchasePriceCents INTEGER, shop TEXT, notes TEXT,
            retired INTEGER NOT NULL DEFAULT 0
        )
        """.trimIndent(),
        0,
    )
    driver.execute(null, "DROP TABLE gear", 0)
    driver.execute(null, "ALTER TABLE gear_v7 RENAME TO gear", 0)
}
