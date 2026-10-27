package no.synth.divelog.core.logbook.io

import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseConnection
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.createDatabaseManager
import co.touchlab.sqliter.withStatement
import co.touchlab.sqliter.withTransaction
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.convert
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * Through the SQLite library under the database driver. The rollback journal keeps the
 * whole database in the one file.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual fun <T> withSqliteFile(initial: ByteArray?, block: (SqliteFile) -> T): Pair<T, ByteArray> {
    val dir = NSTemporaryDirectory()
    val name = "synth-sqlite-${NSUUID().UUIDString}.db"
    val path = dir.trimEnd('/') + "/" + name
    if (initial != null) initial.toNSData().writeToFile(path, atomically = true)
    val fileVersion = userVersion(initial)
    val manager = createDatabaseManager(
        DatabaseConfiguration(
            name = name,
            // sqliter refuses a file newer than this, so open at the file's own version.
            version = maxOf(1, fileVersion),
            create = {},
            upgrade = { _, _, _ -> },
            journalMode = JournalMode.DELETE,
            extendedConfig = DatabaseConfiguration.Extended(basePath = dir),
        ),
    )
    try {
        val connection = manager.createMultiThreadedConnection()
        val result = try {
            // sqliter stamps its own user version on open; put the file's back.
            connection.rawExecSql("PRAGMA user_version = $fileVersion")
            block(SqliterFile(connection))
        } finally {
            connection.close()
        }
        return result to (NSData.dataWithContentsOfFile(path)?.toByteArray() ?: ByteArray(0))
    } finally {
        NSFileManager.defaultManager.removeItemAtPath(path, null)
    }
}

/** The user version in an SQLite file's header (big-endian, offset 60); 0 for a new file. */
private fun userVersion(file: ByteArray?): Int {
    if (file == null || file.size < 64) return 0
    return (60..63).fold(0) { v, i -> (v shl 8) or (file[i].toInt() and 0xFF) }
}

private class SqliterFile(private val c: DatabaseConnection) : SqliteFile {
    override fun query(sql: String, onRow: (SqliteRow) -> Unit) {
        c.withStatement(sql) {
            val cursor = query()
            val row = object : SqliteRow {
                override val columnCount: Int get() = cursor.columnCount
                override fun name(index: Int): String = cursor.columnName(index)
                override fun string(index: Int): String? = if (cursor.isNull(index)) null else cursor.getString(index)
                override fun bytes(index: Int): ByteArray? = if (cursor.isNull(index)) null else cursor.getBytes(index)
            }
            while (cursor.next()) onRow(row)
        }
    }

    override fun execute(sql: String, args: List<Any?>) {
        if (args.isEmpty()) {
            c.rawExecSql(sql)
            return
        }
        c.withStatement(sql) {
            args.forEachIndexed { i, a ->
                val index = i + 1
                when (a) {
                    null -> bindNull(index)
                    is String -> bindString(index, a)
                    is Long -> bindLong(index, a)
                    is Int -> bindLong(index, a.toLong())
                    is Double -> bindDouble(index, a)
                    is ByteArray -> bindBlob(index, a)
                    else -> error("Cannot bind ${a::class.simpleName}")
                }
            }
            execute()
        }
    }

    override fun transaction(block: () -> Unit) {
        c.withTransaction { block() }
    }
}

@OptIn(ExperimentalForeignApi::class)
fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

@OptIn(ExperimentalForeignApi::class)
fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}
