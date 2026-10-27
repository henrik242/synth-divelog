package no.synth.divelog.core.logbook.io

import app.cash.sqldelight.Query
import no.synth.divelog.core.db.AppDatabase
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearKind
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.Sample
import no.synth.divelog.core.model.Site
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Instant

class LogbookBackupTest {
    private val database = createDatabase()
    private val container = AppContainer(database)
    private val backup = LogbookBackup(database) { Instant.parse("2026-10-27T12:00:00Z") }

    /** Two downloaded dives with samples, events and raw data, one deleted, plus gear, buddies, tags and a site. */
    private fun fillLogbook() {
        val device = container.devices.add(Device(vendor = "Shearwater", model = "Petrel", serial = "1a2b3c4d"))
        val placeId = container.sites.getOrCreatePlace("Norway", "Drøbak")
        val siteId = container.sites.addSite(Site(name = "The Wall", placeId = placeId, latitude = 59.663821234567891, longitude = -10.0 / 3))
        val dives = (0..1).map { i ->
            container.dives.importAsNewDive(
                IncomingDive(
                    deviceId = device,
                    startEpochSeconds = 1_700_000_000L + i * 86_400,
                    utcOffsetSeconds = 3_600,
                    durationSeconds = 2_400,
                    maxDepthMm = 30_000,
                    rawData = ByteArray(4_000) { (it * 7 + i).toByte() },
                    rawFormatId = "test-format",
                    fingerprint = "fp-$i",
                    samples = (0..200).map { t -> Sample(timeOffsetSeconds = t * 10, depthMm = t * 100, temperatureMk = 283_150, tankPressuresMbar = mapOf(0 to 200_000 - t * 500)) },
                    events = listOf(Event(timeOffsetSeconds = 60, type = EventType.BOOKMARK, value = 1)),
                ),
            ).diveId
        }
        val kept = dives[0]
        container.dives.setSite(kept, siteId)
        container.buddies.linkToDive(kept, container.buddies.add("Alex"))
        container.tags.linkToDive(kept, container.tags.add("Wreck"))
        container.gear.linkToDive(kept, container.gear.add(GearItem(name = "Regulator", kind = GearKind.FIRST_STAGE)))
        container.dives.deleteDive(dives[1])
    }

    /** Every row of every table, each value as its type and bytes, so a restore can be compared exactly. */
    private fun dump(db: AppDatabase): Map<String, List<String>> = withDbFile(ByteArray(0)) { path ->
        File(path).delete()
        db.backupTo(path)
        DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            db.tableColumns().mapValues { (table, columns) ->
                val values = columns.joinToString(" || '|' || ") { "typeof(\"$it\") || ':' || hex(\"$it\")" }
                c.rows("SELECT $values FROM \"$table\" ORDER BY rowid")
            }
        }
    }

    @Test
    fun backupIsTheDatabaseNamedForTheTimeInUtc() {
        container.buddies.add("Alex")

        val file = backup.create()
        assertEquals("synth-divelog-backup-2026-10-27T120000Z.db.gz", file.name)
        assertEquals("application/gzip", file.mimeType)
        assertTrue(LogbookBackup.NAME.matches(file.name))

        withDbFile(gunzip(file.bytes)) { path ->
            val version = DriverManager.getConnection("jdbc:sqlite:$path").use { it.rows("PRAGMA user_version").single() }
            assertEquals(DiveDatabase.Schema.version.toString(), version)
            assertEquals(listOf("Alex"), AppContainer(createDatabase(path)).buddies.all().map { it.name })
        }
    }

    @Test
    fun restoreBringsBackEveryRowExactly() {
        fillLogbook()
        val before = dump(database)
        val file = backup.create()

        container.dives.deleteDives(container.dives.allDives().map { it.id })
        container.buddies.add("Sam")

        assertEquals(BackupInfo(dives = 1), backup.inspect(file.bytes))
        backup.restore(file.bytes)

        assertEquals(before, dump(database))
        assertTrue(before.getValue("sample").size > 200)
        assertTrue(before.getValue("deletedRecord").isNotEmpty())
    }

    @Test
    fun restoreKeepsTextWhole() {
        val name = "Alex\u0000Sam 'Ø' 🤿"
        container.buddies.add(name)
        container.buddies.add("")
        val file = backup.create()
        container.buddies.add("Kim")

        backup.restore(file.bytes)

        assertEquals(listOf("", name), container.buddies.all().map { it.name }.sorted())
    }

    @Test
    fun restoreTellsObservedQueries() {
        val file = backup.create()
        container.buddies.add("Sam")
        var changes = 0
        database.peopleQueries.selectAllBuddies().addListener(Query.Listener { changes++ })

        backup.restore(file.bytes)

        assertTrue(changes > 0)
        assertEquals(emptyList(), container.buddies.all())
    }

    @Test
    fun restoreRepairsRowsADeletedDiveLeftBehind() {
        fillLogbook()
        // A backup from a desktop that did not enforce foreign keys: deleting the dive left its record, samples and site link.
        val orphaned = withDbFile(gunzip(backup.create().bytes)) { path ->
            DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
                c.run("DELETE FROM dive")
                c.run("DELETE FROM site")
            }
            File(path).readBytes()
        }

        backup.restore(gzip(orphaned))

        assertEquals(emptyList(), container.dives.allDives())
        assertEquals(0, dump(database).getValue("sample").size)
        assertEquals(0, dump(database).getValue("diveComputerRecord").size)
    }

    @Test
    fun restoreMigratesAnOlderBackup() {
        // A version-7 backup: no computer gear yet, so the migration makes one for the device.
        val old = withDbFile(gunzip(backup.create().bytes)) { path ->
            DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
                c.run("DROP INDEX gear_device")
                c.run(
                    "CREATE TABLE gear_v7 (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, kind TEXT, brand TEXT, " +
                        "model TEXT, serialNumber TEXT, purchasedEpochDay INTEGER, purchasePriceCents INTEGER, shop TEXT, " +
                        "notes TEXT, retired INTEGER NOT NULL DEFAULT 0)",
                )
                c.run("DROP TABLE gear")
                c.run("ALTER TABLE gear_v7 RENAME TO gear")
                c.run("INSERT INTO device(vendor, model, serial) VALUES ('Shearwater', 'Petrel', '1a2b3c4d')")
                c.run("PRAGMA user_version = 7")
            }
            File(path).readBytes()
        }

        backup.restore(gzip(old))

        val computer = container.gear.all().single()
        assertEquals(GearKind.COMPUTER, computer.kind)
        assertEquals("1a2b3c4d", computer.serialNumber)
    }

    @Test
    fun restoreRefusesWhatItCannotUse() {
        container.buddies.add("Alex")
        val current = gunzip(backup.create().bytes)
        val newer = withDbFile(current) { path ->
            DriverManager.getConnection("jdbc:sqlite:$path").use { it.run("PRAGMA user_version = 99") }
            File(path).readBytes()
        }
        val otherDatabase = withDbFile(ByteArray(0)) { path ->
            DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
                c.run("CREATE TABLE notes(text TEXT)")
                c.run("PRAGMA user_version = 3")
            }
            File(path).readBytes()
        }

        assertFailsWith<IllegalArgumentException> { backup.restore(gzip(newer)) }
        assertFailsWith<IllegalArgumentException> { backup.restore("not a backup".encodeToByteArray()) }
        assertFailsWith<IllegalArgumentException> { backup.inspect(gzip("not a backup".encodeToByteArray())) }
        assertFailsWith<IllegalArgumentException> { backup.inspect(gzip(current).copyOf(200)) }
        assertFailsWith<IllegalArgumentException> { backup.inspect(otherDatabase) }
        assertEquals(listOf("Alex"), container.buddies.all().map { it.name })
        // An uncompressed database file is a backup too.
        assertEquals(BackupInfo(dives = 0), backup.inspect(current))
    }

    private fun Connection.run(sql: String) = createStatement().use { it.execute(sql) }

    private fun Connection.rows(sql: String): List<String> = createStatement().use { st ->
        st.executeQuery(sql).use { rs -> buildList { while (rs.next()) add(rs.getString(1)) } }
    }

    private fun <T> withDbFile(bytes: ByteArray, block: (path: String) -> T): T {
        val file = File.createTempFile("backup-test", ".db")
        try {
            file.writeBytes(bytes)
            return block(file.path)
        } finally {
            file.delete()
        }
    }
}
