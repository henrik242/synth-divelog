package no.synth.divelog.ui.io

import co.touchlab.sqliter.DatabaseConfiguration
import co.touchlab.sqliter.DatabaseConnection
import co.touchlab.sqliter.JournalMode
import co.touchlab.sqliter.createDatabaseManager
import co.touchlab.sqliter.withStatement
import co.touchlab.sqliter.withTransaction
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.ZLIB_VERSION
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2_
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2_
import platform.zlib.z_stream

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
    val manager = createDatabaseManager(
        DatabaseConfiguration(
            name = name,
            version = 1,
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
            connection.rawExecSql("PRAGMA user_version = ${userVersion(initial)}")
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
internal fun ByteArray.toNSData(): NSData =
    if (isEmpty()) NSData() else usePinned { NSData.create(bytes = it.addressOf(0), length = size.convert()) }

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val out = ByteArray(length.toInt())
    if (out.isNotEmpty()) out.usePinned { memcpy(it.addressOf(0), bytes, length) }
    return out
}

/** zlib with a gzip wrapper (window bits 15 + 16). */
private const val GZIP_WINDOW_BITS = 31
private const val CHUNK = 64 * 1024

@OptIn(ExperimentalForeignApi::class)
internal actual fun gunzip(data: ByteArray): ByteArray = memScoped {
    val strm = alloc<z_stream>()
    check(inflateInit2_(strm.ptr, GZIP_WINDOW_BITS, ZLIB_VERSION, sizeOf<z_stream>().convert()) == Z_OK) { "inflate init failed" }
    val out = ArrayList<ByteArray>()
    try {
        data.usePinned { input ->
            strm.next_in = if (data.isEmpty()) null else input.addressOf(0).reinterpret()
            strm.avail_in = data.size.convert()
            val chunk = ByteArray(CHUNK)
            var rc: Int
            do {
                rc = chunk.usePinned { c ->
                    strm.next_out = c.addressOf(0).reinterpret()
                    strm.avail_out = CHUNK.convert()
                    inflate(strm.ptr, Z_NO_FLUSH)
                }
                check(rc == Z_OK || rc == Z_STREAM_END || rc == Z_BUF_ERROR) { "inflate failed: $rc" }
                val produced = CHUNK - strm.avail_out.toInt()
                if (produced > 0) out += chunk.copyOf(produced)
                if (rc == Z_BUF_ERROR && produced == 0) break
            } while (rc != Z_STREAM_END)
        }
    } finally {
        inflateEnd(strm.ptr)
    }
    out.fold(ByteArray(0)) { acc, b -> acc + b }
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun gzip(data: ByteArray): ByteArray = memScoped {
    val strm = alloc<z_stream>()
    val init = deflateInit2_(
        strm.ptr, Z_DEFAULT_COMPRESSION, Z_DEFLATED, GZIP_WINDOW_BITS, 8, Z_DEFAULT_STRATEGY,
        ZLIB_VERSION, sizeOf<z_stream>().convert(),
    )
    check(init == Z_OK) { "deflate init failed" }
    val out = ArrayList<ByteArray>()
    try {
        data.usePinned { input ->
            strm.next_in = if (data.isEmpty()) null else input.addressOf(0).reinterpret()
            strm.avail_in = data.size.convert()
            val chunk = ByteArray(CHUNK)
            var rc: Int
            do {
                rc = chunk.usePinned { c ->
                    strm.next_out = c.addressOf(0).reinterpret()
                    strm.avail_out = CHUNK.convert()
                    deflate(strm.ptr, Z_FINISH)
                }
                check(rc == Z_OK || rc == Z_STREAM_END || rc == Z_BUF_ERROR) { "deflate failed: $rc" }
                val produced = CHUNK - strm.avail_out.toInt()
                if (produced > 0) out += chunk.copyOf(produced)
            } while (rc != Z_STREAM_END)
        }
    } finally {
        deflateEnd(strm.ptr)
    }
    out.fold(ByteArray(0)) { acc, b -> acc + b }
}
