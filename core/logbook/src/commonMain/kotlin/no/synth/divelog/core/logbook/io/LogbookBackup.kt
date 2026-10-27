package no.synth.divelog.core.logbook.io

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import no.synth.divelog.core.db.AppDatabase
import no.synth.divelog.core.db.AppDatabase.Companion.quoteName
import no.synth.divelog.core.db.RowQuery
import no.synth.kmpzip.gzip.GzipException
import no.synth.kmpzip.gzip.isGzip
import kotlin.time.Clock
import kotlin.time.Instant

/** What a backup file holds, read before it is restored. */
data class BackupInfo(val dives: Long)

/**
 * A full backup of the logbook: a gzipped copy of the database file. It holds everything,
 * raw dive-computer data and deleted-dive markers included, and carries its schema version,
 * so an older backup can be migrated on restore. Settings are not in it.
 */
class LogbookBackup(private val database: AppDatabase, private val now: () -> Instant = { Clock.System.now() }) {
    /**
     * The backup file, named [prefix] and the time in UTC, so every backup gets a name of its own
     * and names sort by time wherever the device has been. Blocks; call it off the main thread.
     */
    fun create(prefix: String = PREFIX): ExportFile {
        val bytes = withTempFile { path -> database.backupTo(path) }
        val t = now().toLocalDateTime(TimeZone.UTC)
        val time = listOf(t.hour, t.minute, t.second).joinToString("") { it.toString().padStart(2, '0') }
        return ExportFile("$prefix-${t.date}T${time}Z.db.gz", MIME_TYPE, gzip(bytes))
    }

    /** Checks that [file] is a backup this app can restore and says what is in it. */
    fun inspect(file: ByteArray): BackupInfo {
        val db = unpack(file)
        checkedVersion(db)
        val (dives, _) = withSqliteFile(db, readBack = false) { backup ->
            checkedTables(backup)
            backup.rows().rows("SELECT count(*) FROM dive", 1).single().single()?.toLongOrNull() ?: 0
        }
        return BackupInfo(dives)
    }

    /**
     * Replaces the whole logbook with the one in backup [file], migrating an older backup
     * first. Nothing changes unless all of it is restored. Blocks; call it off the main thread.
     */
    fun restore(file: ByteArray) {
        val db = unpack(file)
        val version = checkedVersion(db)
        withSqliteFile(db, readBack = false) { backup ->
            backup.transaction { AppDatabase.migrate(version) { backup.execute(it) } }
            // A backup from a desktop that did not yet enforce foreign keys can hold rows a deleted dive left behind.
            backup.transaction { AppDatabase.repairForeignKeys(backup.rows()) { backup.execute(it) } }
            val tables = checkedTables(backup)
            require(tables.mapValues { it.value.toSet() } == database.tableColumns().mapValues { it.value.toSet() }) {
                "the backup's tables do not match this version of the app"
            }
            database.replaceAllRows { execute -> tables.forEach { (table, columns) -> copyRows(backup, table, columns, execute) } }
        }
    }

    /** The database file in [file], gzipped (as [create] writes it) or not. */
    private fun unpack(file: ByteArray): ByteArray {
        val db = if (isGzip(file)) {
            try {
                gunzip(file)
            } catch (e: GzipException) {
                throw IllegalArgumentException(NOT_A_BACKUP, e)
            }
        } else {
            file
        }
        require(db.size >= 100 && db.copyOf(SQLITE_MAGIC.length).decodeToString() == SQLITE_MAGIC) { NOT_A_BACKUP }
        return db
    }

    /** The schema version in [db]'s header, if this app can restore it. */
    private fun checkedVersion(db: ByteArray): Long {
        val version = (60..63).fold(0L) { v, i -> (v shl 8) or (db[i].toLong() and 0xFF) }
        require(version > 0) { NOT_A_BACKUP }
        require(version <= AppDatabase.schemaVersion) { "the backup is from a newer version of the app; update the app to restore it" }
        return version
    }

    /** [backup]'s tables and their columns, if it has a logbook's. */
    private fun checkedTables(backup: SqliteFile): Map<String, List<String>> =
        AppDatabase.tableColumns(backup.rows()).also { require("dive" in it && "diveComputerRecord" in it) { NOT_A_BACKUP } }

    private fun SqliteFile.rows() = RowQuery { sql, columns ->
        buildList { query(sql) { row -> add((0 until columns).map { row.string(it) }) } }
    }

    /**
     * Inserts [table]'s rows from [backup] through [execute], many rows to a statement. Each value
     * becomes a literal of its own type, so nothing is converted: quote() for most, but text as its
     * bytes in hex cast back to text, since quote() cuts text off at an embedded NUL.
     */
    private fun copyRows(backup: SqliteFile, table: String, columns: List<String>, execute: (String) -> Unit) {
        val insert = "INSERT INTO ${quoteName(table)} (${columns.joinToString(", ") { quoteName(it) }}) VALUES "
        val select = "SELECT ${columns.joinToString(", ") { literal(quoteName(it)) }} FROM ${quoteName(table)}"
        val values = StringBuilder()
        var rows = 0
        fun flush() {
            if (rows == 0) return
            execute(insert + values)
            values.clear()
            rows = 0
        }
        backup.query(select) { row ->
            if (rows > 0) values.append(", ")
            values.append('(')
            for (i in 0 until row.columnCount) {
                if (i > 0) values.append(", ")
                values.append(row.string(i) ?: "NULL")
            }
            values.append(')')
            rows++
            if (rows >= BATCH_ROWS || values.length >= BATCH_CHARS) flush()
        }
        flush()
    }

    /** SQL that turns [column]'s value into an SQL literal of the same type and content. */
    private fun literal(column: String): String =
        "CASE typeof($column) WHEN 'text' THEN 'CAST(X''' || hex($column) || ''' AS TEXT)' ELSE quote($column) END"

    companion object {
        const val MIME_TYPE = "application/gzip"
        const val PREFIX = "synth-divelog-backup"

        /** The name of the copy of the logbook made before a restore replaces it; never pruned. */
        const val BEFORE_RESTORE_PREFIX = "synth-divelog-before-restore"

        /**
         * The names [create] gives backups: the date, then the time of day. Older backups were named
         * by local date, some with the local time after a hyphen.
         */
        val NAME = Regex("$PREFIX-(\\d{4}-\\d{2}-\\d{2})(?:[-T](\\d{6})Z?)?\\.db\\.gz")

        private const val NOT_A_BACKUP = "not a Synth Divelog backup"
        private const val SQLITE_MAGIC = "SQLite format 3\u0000"
        private const val BATCH_ROWS = 500
        private const val BATCH_CHARS = 256 * 1024
    }
}

/** Runs [block] with the path of a new, empty temporary file and returns the file's bytes afterwards. */
internal expect fun withTempFile(block: (path: String) -> Unit): ByteArray
