package no.synth.divelog.core.logbook.io

import app.cash.sqldelight.Query
import co.touchlab.sqliter.DatabaseFileContext
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.model.GearKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Backup and restore through the native driver and the platform SQLite. */
class LogbookBackupIosTest {
    @Test
    fun backupAndRestore() {
        DatabaseFileContext.deleteDatabase("divelog.db")
        val database = createDatabase()
        val container = AppContainer(database)
        val backup = LogbookBackup(database)
        container.buddies.add("Alex")
        val file = backup.create()

        container.buddies.add("Sam")
        var changes = 0
        database.peopleQueries.selectAllBuddies().addListener(Query.Listener { changes++ })
        assertEquals(BackupInfo(dives = 0), backup.inspect(file.bytes))
        backup.restore(file.bytes)
        assertEquals(listOf("Alex"), container.buddies.all().map { it.name })
        assertTrue(changes > 0)

        // A version-7 backup: no computer gear yet, so the migration makes one for the device.
        val (_, old) = withSqliteFile(gunzip(file.bytes)) { db ->
            db.execute("DROP INDEX gear_device")
            db.execute(
                "CREATE TABLE gear_v7 (id INTEGER PRIMARY KEY AUTOINCREMENT, name TEXT NOT NULL, kind TEXT, brand TEXT, " +
                    "model TEXT, serialNumber TEXT, purchasedEpochDay INTEGER, purchasePriceCents INTEGER, shop TEXT, " +
                    "notes TEXT, retired INTEGER NOT NULL DEFAULT 0)",
            )
            db.execute("DROP TABLE gear")
            db.execute("ALTER TABLE gear_v7 RENAME TO gear")
            db.execute("INSERT INTO device(vendor, model, serial) VALUES ('Shearwater', 'Petrel', '1a2b3c4d')")
            db.execute("PRAGMA user_version = 7")
        }
        backup.restore(gzip(old))
        val computer = container.gear.all().single()
        assertEquals(GearKind.COMPUTER, computer.kind)
        assertEquals("1a2b3c4d", computer.serialNumber)
    }
}
