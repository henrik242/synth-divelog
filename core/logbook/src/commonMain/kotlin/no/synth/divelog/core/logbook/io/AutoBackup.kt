package no.synth.divelog.core.logbook.io

import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.LocalDate
import no.synth.divelog.core.logbook.reason
import no.synth.divelog.core.logbook.settings.AppSettings
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Instant

/** A folder automatic backups go to, as the platform reaches it. Blocking; call it off the main thread. */
interface BackupFolder {
    /** The names of the files in the folder. */
    fun list(): List<String>

    /**
     * Writes [bytes] to the file [name]. A file of that name already there is replaced or, where the
     * platform cannot replace one, kept beside the new one.
     */
    fun write(name: String, bytes: ByteArray)

    fun delete(name: String)
}

/** How a platform lets the user pick a backup folder and reaches it again later. */
interface BackupFolders {
    /** Asks the user for a folder and returns its location to keep, or null if cancelled. */
    suspend fun pick(): String?

    /** The folder at [location], or null if it can no longer be reached. */
    fun open(location: String): BackupFolder?

    /** [location] as the user knows it. */
    fun label(location: String): String

    /** Gives up any access [pick] was granted to [location]. */
    fun release(location: String) {}

    /**
     * A new location to keep in place of [location] when the platform says the old one is going
     * stale (an iOS bookmark to a folder that moved), or null to keep it.
     */
    fun renewed(location: String): String? = null
}

/**
 * Keeps track of backups: whether the logbook changed since the last automatic one, writing it
 * to the backup folder and pruning old ones there, and when to remind the user to back up.
 */
class AutoBackup(
    private val backup: LogbookBackup,
    private val settings: AppSettings,
    private val now: () -> Instant = { Clock.System.now() },
) {
    /** Held while a backup is written to the folder, so writes and prunes never overlap. */
    private val writing = Mutex()

    /**
     * The logbook changed since the last automatic backup that worked began, or the backup folder
     * has none yet. Kept until a backup works, so one the app did not live to finish is made at the
     * next start.
     */
    val pending: Boolean
        get() = settings.backupChanges > settings.backupChangesSaved ||
            (settings.backupFolder != null && settings.lastAutoBackupEpochSeconds == null)

    /** Counts a change to the logbook. Called from one place at a time, the one that follows its changes. */
    fun markChanged() {
        settings.backupChanges += 1
    }

    /** Notes a backup the user saved to a file. */
    fun recordBackup() {
        settings.lastBackupEpochSeconds = now().epochSeconds
    }

    /** When the last backup was saved to a file or written automatically. */
    val lastBackupEpochSeconds: Long? get() = settings.lastBackupEpochSeconds

    /** Where automatic backups go, or null when they are off. */
    val folder: String? get() = settings.backupFolder

    /** When the last automatic backup to [folder] was written. */
    val lastWrittenEpochSeconds: Long? get() = settings.lastAutoBackupEpochSeconds

    /** Why the last automatic backup failed and when, or null if it did not. */
    val lastFailure: Pair<String, Long>?
        get() = settings.autoBackupError?.let { error -> settings.autoBackupErrorEpochSeconds?.let { error to it } }

    /**
     * Sends automatic backups to [location] from now on, or turns them off for null. A new
     * folder starts with no history, so a backup is [pending] for it at once.
     */
    fun useFolder(location: String?) {
        settings.backupFolder = location
        settings.lastAutoBackupEpochSeconds = null
        settings.autoBackupError = null
    }

    /**
     * Writes a backup to the backup [folder], reached through [folders], then [prune]s the folder.
     * The outcome is recorded only if that is still the backup folder when it is done; a failure
     * is kept for [lastFailure] and thrown. Does nothing when automatic backups are off, and leaves
     * the backup [pending] when one of the same name, from the same second, is there already.
     */
    suspend fun write(folders: BackupFolders): Unit = writing.withLock {
        val location = settings.backupFolder ?: return@withLock
        // The changes this backup holds; one made while it is written stays pending.
        val holds = settings.backupChanges
        var target = location
        try {
            // Kept only while that is still the folder; the user may have picked another meanwhile.
            target = folders.renewed(location)?.takeIf { settings.backupFolder == location }?.also { settings.backupFolder = it } ?: location
            val (folder, name) = writeFile(folders, target, LogbookBackup.PREFIX) ?: return@withLock
            prune(folder, keep = name)
            if (settings.backupFolder == target) {
                val written = now().epochSeconds
                settings.lastBackupEpochSeconds = written
                settings.lastAutoBackupEpochSeconds = written
                settings.autoBackupError = null
                settings.backupChangesSaved = holds
            }
        } catch (e: Throwable) {
            if (settings.backupFolder == target) {
                settings.autoBackupError = e.reason
                settings.autoBackupErrorEpochSeconds = now().epochSeconds
            }
            throw e
        }
    }

    /**
     * Writes a copy of the logbook as it is now to the backup folder, named so that pruning leaves
     * it alone, for a restore about to replace it. False when there is no backup folder.
     */
    suspend fun writeBeforeRestore(folders: BackupFolders): Boolean = writing.withLock {
        val location = settings.backupFolder ?: return@withLock false
        // One of the same name is a copy from the same second, which will do.
        writeFile(folders, location, LogbookBackup.BEFORE_RESTORE_PREFIX)
        true
    }

    /**
     * Writes a new backup named [prefix] to the folder at [location] and returns the folder and the
     * file's name, or null when a file of that name is there already: it is never written over, since
     * not every platform can replace a file, and some would keep the new one under another name. A
     * write that fails takes its partial file with it.
     */
    private fun writeFile(folders: BackupFolders, location: String, prefix: String): Pair<BackupFolder, String>? {
        val folder = folders.open(location) ?: error("the folder can no longer be reached; choose it again")
        val file = backup.create(prefix)
        if (file.name in folder.list()) return null
        try {
            folder.write(file.name, file.bytes)
        } catch (e: Throwable) {
            runCatching { folder.delete(file.name) }
            throw e
        }
        return folder to file.name
    }

    /**
     * Makes automatic backups until cancelled. [wake] signals a change to the logbook. A backup
     * already [pending] goes at once; after that each waits until the logbook has gone [settle]
     * without a change. [write] makes one and says whether it worked; a failed one is tried again
     * after the next change rather than at once.
     */
    suspend fun keepBackingUp(wake: ReceiveChannel<Unit>, settle: Duration, write: suspend () -> Boolean) {
        var waitFirst = false
        while (true) {
            if (!pending) {
                wake.receive()
                waitFirst = true
            }
            if (waitFirst) {
                while (withTimeoutOrNull(settle) { wake.receive() } != null) Unit
            }
            waitFirst = true
            if (!write()) wake.receive()
        }
    }

    private class Dated(val name: String, val date: LocalDate, val time: String)

    /**
     * Keeps, of the backups in [folder], the newest of each of the last [KEEP_DAYS] days, [KEEP_WEEKS]
     * weeks and [KEEP_MONTHS] months that have one, and [keep], and deletes the rest. Files that are
     * not backups are left alone, and a backup that cannot be deleted stays for the next prune.
     */
    private fun prune(folder: BackupFolder, keep: String) {
        val newestFirst = folder.list()
            .mapNotNull { name ->
                LogbookBackup.NAME.matchEntire(name)?.let { m -> Dated(name, LocalDate.parse(m.groupValues[1]), m.groupValues[2]) }
            }
            .sortedWith(compareByDescending<Dated> { it.date }.thenByDescending { it.time })
        fun newestOf(count: Int, period: (LocalDate) -> Long) = newestFirst.distinctBy { period(it.date) }.take(count).map { it.name }
        val kept = buildSet {
            add(keep)
            addAll(newestOf(KEEP_DAYS) { it.toEpochDays().toLong() })
            // Weeks from Monday: day 0 of the epoch was a Thursday.
            addAll(newestOf(KEEP_WEEKS) { (it.toEpochDays().toLong() + 3) / 7 })
            addAll(newestOf(KEEP_MONTHS) { it.year * 12L + it.month.ordinal })
        }
        newestFirst.filter { it.name !in kept }.forEach { runCatching { folder.delete(it.name) } }
    }

    /** Whole days since the last backup, or null if there has been none. */
    fun daysSinceBackup(): Long? = settings.lastBackupEpochSeconds?.let { (now().epochSeconds - it) / DAY_SECONDS }

    /**
     * Whether to remind the user to back up a logbook of [dives] dives: it has some, the last backup
     * is [REMIND_AFTER_DAYS] days old or missing, nothing up to date is in the backup folder (there
     * is none, or the automatic backup has not worked since the logbook changed), and the last
     * reminder is [REMIND_EVERY_DAYS] days old. A true answer counts as the reminder.
     */
    fun takeReminder(dives: Long): Boolean {
        val nowSeconds = now().epochSeconds
        val backupOld = daysSinceBackup()?.let { it >= REMIND_AFTER_DAYS } ?: true
        val upToDate = settings.backupFolder != null && !pending
        val remindedLately = settings.backupReminderEpochSeconds?.let { nowSeconds - it < REMIND_EVERY_DAYS * DAY_SECONDS } ?: false
        if (dives == 0L || !backupOld || upToDate || remindedLately) return false
        settings.backupReminderEpochSeconds = nowSeconds
        return true
    }

    companion object {
        /** How far back the folder keeps the newest backup of each day, week and month. */
        const val KEEP_DAYS = 7
        const val KEEP_WEEKS = 4
        const val KEEP_MONTHS = 12
        const val REMIND_AFTER_DAYS = 30L
        const val REMIND_EVERY_DAYS = 14L
        private const val DAY_SECONDS = 86_400L
    }
}
