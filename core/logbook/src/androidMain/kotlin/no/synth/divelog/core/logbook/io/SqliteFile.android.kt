package no.synth.divelog.core.logbook.io

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Through the platform SQLite. NO_LOCALIZED_COLLATORS keeps Android from adding its
 * own metadata table to the file.
 */
internal actual fun <T> withSqliteFile(initial: ByteArray?, block: (SqliteFile) -> T): Pair<T, ByteArray> {
    val file = File.createTempFile("synth-sqlite", ".db")
    try {
        if (initial != null) file.writeBytes(initial) else file.delete()
        val flags = SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
        val db = SQLiteDatabase.openDatabase(file.path, null, flags)
        val result = try {
            block(AndroidFile(db))
        } finally {
            db.close()
        }
        return result to file.readBytes()
    } finally {
        file.delete()
        File(file.path + "-journal").delete()
    }
}

private class AndroidFile(private val db: SQLiteDatabase) : SqliteFile {
    override fun query(sql: String, onRow: (SqliteRow) -> Unit) {
        db.rawQuery(sql, null).use { c ->
            val row = AndroidRow(c)
            while (c.moveToNext()) onRow(row)
        }
    }

    override fun execute(sql: String, args: List<Any?>) {
        if (args.isEmpty()) db.execSQL(sql) else db.execSQL(sql, args.map { if (it is Int) it.toLong() else it }.toTypedArray())
    }

    override fun transaction(block: () -> Unit) {
        db.beginTransaction()
        try {
            block()
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }
}

private class AndroidRow(private val c: Cursor) : SqliteRow {
    override val columnCount: Int get() = c.columnCount

    override fun name(index: Int): String = c.getColumnName(index)

    override fun string(index: Int): String? = if (c.isNull(index)) null else c.getString(index)

    override fun bytes(index: Int): ByteArray? = if (c.isNull(index)) null else c.getBlob(index)
}

internal actual fun gzip(data: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

internal actual fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBytes() }
