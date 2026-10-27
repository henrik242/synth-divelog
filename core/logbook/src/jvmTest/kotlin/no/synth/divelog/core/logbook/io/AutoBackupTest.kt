package no.synth.divelog.core.logbook.io

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.LocalDate
import kotlinx.datetime.plus
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.logbook.settings.AppSettings
import no.synth.divelog.core.logbook.settings.SettingsStore
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class AutoBackupTest {
    private class MemoryStore : SettingsStore {
        val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) { values[key] = value }
    }

    private open class MemoryFolder(vararg names: String) : BackupFolder {
        val files = names.associateWith { byteArrayOf(9) }.toMutableMap()
        override fun list(): List<String> = files.keys.toList()
        override fun write(name: String, bytes: ByteArray) { files[name] = bytes }
        override fun delete(name: String) { files.remove(name) }
    }

    /** A folder whose writes leave a partial file and then fail. */
    private class FailingFolder(vararg names: String) : MemoryFolder(*names) {
        override fun write(name: String, bytes: ByteArray) {
            files[name] = bytes.copyOf(1)
            throw IllegalStateException("Disk full")
        }
    }

    /** Folders by location; a location with no folder cannot be reached. */
    private class MemoryFolders(private val folders: Map<String, BackupFolder>, private val renew: Map<String, String> = emptyMap()) : BackupFolders {
        override suspend fun pick(): String? = null
        override fun open(location: String): BackupFolder? = folders[location]
        override fun label(location: String): String = location
        override fun renewed(location: String): String? = renew[location]
    }

    private val database = createDatabase()
    private val container = AppContainer(database)
    private val settings = AppSettings(MemoryStore())
    private var now = Instant.parse("2026-10-27T12:00:00Z")
    private val auto = AutoBackup(LogbookBackup(database) { now }, settings) { now }

    private fun backupsOf(folder: MemoryFolder) = folder.files.keys.filter { LogbookBackup.NAME.matches(it) }.toSet()

    @Test
    fun writesABackupAndKeepsOneADayAWeekAndAMonth() {
        // One backup a day from 2025-09-01 to 2026-10-25, two on 2026-10-26, in the name forms used over time.
        val days = generateSequence(LocalDate.parse("2025-09-01")) { it.plus(DatePeriod(days = 1)) }.takeWhile { it < LocalDate.parse("2026-10-26") }
        val folder = MemoryFolder(
            *days.map { "synth-divelog-backup-${it}T060000Z.db.gz" }.toList().toTypedArray(),
            "synth-divelog-backup-2026-10-26.db.gz",
            "synth-divelog-backup-2026-10-26-090000.db.gz",
            "synth-divelog-backup-2026-10-26T170000Z.db.gz",
            "synth-divelog-before-restore-2026-01-01T000000Z.db.gz",
            "notes.txt",
        )
        auto.useFolder("backups")

        runTest { auto.write(MemoryFolders(mapOf("backups" to folder))) }

        assertFalse(auto.pending)
        assertEquals(0L, auto.daysSinceBackup())
        assertEquals(now.epochSeconds, auto.lastWrittenEpochSeconds)
        assertNull(auto.lastFailure)
        val utc = { date: String -> "synth-divelog-backup-${date}T060000Z.db.gz" }
        val expected = setOf(
            "synth-divelog-backup-2026-10-27T120000Z.db.gz",
            "synth-divelog-backup-2026-10-26T170000Z.db.gz",
        ) + listOf("2026-10-25", "2026-10-24", "2026-10-23", "2026-10-22", "2026-10-21").map(utc) +
            // The newest of the weeks before, from Monday.
            listOf("2026-10-18", "2026-10-11").map(utc) +
            // The newest of the months before, back to the twelfth.
            listOf(
                "2026-09-30", "2026-08-31", "2026-07-31", "2026-06-30", "2026-05-31", "2026-04-30", "2026-03-31",
                "2026-02-28", "2026-01-31", "2025-12-31", "2025-11-30",
            ).map(utc)
        assertEquals(expected, backupsOf(folder))
        assertTrue(folder.files.getValue("synth-divelog-backup-2026-10-27T120000Z.db.gz").size > 1)
        assertTrue("notes.txt" in folder.files)
        assertTrue("synth-divelog-before-restore-2026-01-01T000000Z.db.gz" in folder.files)
    }

    @Test
    fun aFailedWriteLeavesTheOldBackupsAndNoPartialFile() = runTest {
        val old = (1..20).map { "synth-divelog-backup-2026-09-%02dT060000Z.db.gz".format(it) }.toTypedArray()
        val folder = FailingFolder(*old)
        auto.useFolder("backups")

        assertFailsWith<IllegalStateException> { auto.write(MemoryFolders(mapOf("backups" to folder))) }

        assertEquals(old.toSet(), folder.files.keys)
    }

    @Test
    fun aBackupNeverWritesOverAFileOfTheSameName() = runTest {
        val sameSecond = "synth-divelog-backup-2026-10-27T120000Z.db.gz"
        val folder = FailingFolder(sameSecond)
        auto.useFolder("backups")

        auto.write(MemoryFolders(mapOf("backups" to folder)))

        assertContentEquals(byteArrayOf(9), folder.files[sameSecond])
        assertTrue(auto.pending)
        assertNull(auto.lastFailure)
        assertNull(auto.lastWrittenEpochSeconds)
    }

    @Test
    fun aFailureIsKeptUntilTheNextBackupWorks() = runTest {
        val folders = MemoryFolders(mapOf("broken" to FailingFolder(), "backups" to MemoryFolder()))
        auto.useFolder("broken")

        assertFailsWith<IllegalStateException> { auto.write(folders) }
        assertTrue(auto.pending)
        assertNull(auto.daysSinceBackup())
        assertEquals("Disk full" to now.epochSeconds, auto.lastFailure)

        settings.backupFolder = "missing"
        assertFailsWith<IllegalStateException> { auto.write(folders) }
        assertEquals("the folder can no longer be reached; choose it again", auto.lastFailure?.first)

        now += 1.days
        settings.backupFolder = "backups"
        auto.write(folders)
        assertNull(auto.lastFailure)
        assertFalse(auto.pending)
        assertEquals(now.epochSeconds, auto.lastWrittenEpochSeconds)
    }

    @Test
    fun aNewFolderStartsAfreshWithABackupDue() = runTest {
        auto.useFolder("backups")
        auto.write(MemoryFolders(mapOf("backups" to MemoryFolder())))

        auto.useFolder("other")
        assertTrue(auto.pending)
        assertNull(auto.lastWrittenEpochSeconds)
        assertEquals(0L, auto.daysSinceBackup())

        auto.useFolder(null)
        assertNull(auto.folder)
    }

    @Test
    fun aChangeWhileTheBackupIsWrittenStaysPending() = runTest {
        val folder = object : MemoryFolder() {
            override fun write(name: String, bytes: ByteArray) {
                super.write(name, bytes)
                auto.markChanged()
            }
        }
        auto.useFolder("backups")

        auto.write(MemoryFolders(mapOf("backups" to folder)))

        assertTrue(auto.pending)
        assertNull(auto.lastFailure)
    }

    @Test
    fun aFolderChangedDuringTheWriteGetsNoStatusFromIt() = runTest {
        val folder = object : MemoryFolder() {
            override fun write(name: String, bytes: ByteArray) {
                super.write(name, bytes)
                auto.useFolder("other")
            }
        }
        auto.useFolder("backups")

        auto.write(MemoryFolders(mapOf("backups" to folder)))

        assertEquals("other", auto.folder)
        assertNull(auto.lastWrittenEpochSeconds)
        assertTrue(auto.pending)
    }

    @Test
    fun writesDoNotOverlap() = runBlocking {
        val inside = AtomicInteger()
        var most = 0
        val folder = object : MemoryFolder() {
            override fun write(name: String, bytes: ByteArray) {
                most = maxOf(most, inside.incrementAndGet())
                Thread.sleep(20)
                super.write(name, bytes)
                inside.decrementAndGet()
            }
        }
        val folders = MemoryFolders(mapOf("backups" to folder))
        auto.useFolder("backups")

        withContext(Dispatchers.Default) { (1..4).map { async { auto.write(folders) } }.awaitAll() }

        assertEquals(1, most)
    }

    @Test
    fun theCopyBeforeARestoreIsNeverPruned() = runTest {
        val folder = MemoryFolder()
        val folders = MemoryFolders(mapOf("backups" to folder))
        assertFalse(auto.writeBeforeRestore(folders))

        auto.useFolder("backups")
        assertTrue(auto.writeBeforeRestore(folders))
        val copy = folder.files.keys.single()
        assertEquals("synth-divelog-before-restore-2026-10-27T120000Z.db.gz", copy)
        repeat(40) {
            now += 1.days
            auto.markChanged()
            auto.write(folders)
        }

        assertTrue(copy in folder.files)
    }

    @Test
    fun aRenewedLocationIsKept() = runTest {
        val folder = MemoryFolder()
        auto.useFolder("old")

        auto.write(MemoryFolders(mapOf("new" to folder), renew = mapOf("old" to "new")))

        assertEquals("new", auto.folder)
        assertEquals(1, backupsOf(folder).size)
        assertNull(auto.lastFailure)
    }

    @Test
    fun aRenewedLocationDoesNotReplaceAFolderPickedMeanwhile() = runTest {
        val folder = MemoryFolder()
        val folders = object : BackupFolders {
            override suspend fun pick(): String? = null
            override fun open(location: String): BackupFolder? = folder.takeIf { location == "old" || location == "new" }
            override fun label(location: String): String = location
            override fun renewed(location: String): String {
                auto.useFolder("picked")
                return "new"
            }
        }
        auto.useFolder("old")

        auto.write(folders)

        assertEquals("picked", auto.folder)
        assertNull(auto.lastWrittenEpochSeconds)
    }

    @Test
    fun aPendingBackupGoesAtOnceAndLaterOnesWaitForQuiet() = runTest {
        val changed = Channel<Unit>(Channel.CONFLATED)
        val writes = mutableListOf<Long>()
        val folders = MemoryFolders(mapOf("backups" to MemoryFolder()))
        auto.useFolder("backups")
        backgroundScope.launch {
            val start = now
            auto.keepBackingUp(changed, 1.minutes) {
                writes += currentTime
                // Backups are named by the second, so the clock moves with virtual time.
                now = start + currentTime.milliseconds
                runCatching { auto.write(folders) }.isSuccess
            }
        }
        runCurrent()
        assertEquals(listOf(0L), writes)

        // Changes 30 s apart keep it waiting; it goes a minute after the last one.
        repeat(3) {
            auto.markChanged()
            changed.send(Unit)
            advanceTimeBy(30.seconds)
        }
        runCurrent()
        assertEquals(listOf(0L), writes)
        advanceTimeBy(31.seconds)
        assertEquals(listOf(0L, 120_000L), writes)
        assertFalse(auto.pending)
    }

    @Test
    fun aFailedBackupWaitsForTheNextChange() = runTest {
        val changed = Channel<Unit>(Channel.CONFLATED)
        var attempts = 0
        var works = false
        auto.useFolder("backups")
        backgroundScope.launch {
            auto.keepBackingUp(changed, 1.minutes) {
                attempts++
                works
            }
        }
        runCurrent()
        advanceTimeBy(10.minutes)
        assertEquals(1, attempts)

        works = true
        changed.send(Unit)
        advanceTimeBy(61.seconds)
        assertEquals(2, attempts)
    }

    @Test
    fun remindsWhenNoBackupIsUpToDateAtMostEveryTwoWeeks() = runTest {
        assertFalse(auto.takeReminder(dives = 0))
        assertTrue(auto.takeReminder(dives = 5))
        assertFalse(auto.takeReminder(dives = 5))
        now += 14.days
        assertTrue(auto.takeReminder(dives = 5))

        now += 14.days
        auto.recordBackup()
        assertFalse(auto.takeReminder(dives = 5))
        now += 30.days
        assertTrue(auto.takeReminder(dives = 5))

        // A backup folder that holds the logbook as it is: no reminder, however old the backup.
        auto.useFolder("backups")
        auto.write(MemoryFolders(mapOf("backups" to MemoryFolder())))
        now += 60.days
        assertFalse(auto.takeReminder(dives = 5))

        // Automatic backups that stopped working after the logbook changed: reminded.
        auto.markChanged()
        assertTrue(auto.takeReminder(dives = 5))
    }

    @Test
    fun aCommittedWriteIsAChange() = runBlocking {
        val change = async(Dispatchers.Default) { database.changes().first() }
        // The listener goes on once the flow is collected, so keep writing until it hears one.
        withTimeout(5_000) {
            var n = 0
            while (!change.isCompleted) {
                container.buddies.add("Buddy ${n++}")
                delay(10)
            }
        }
    }
}
