package no.synth.divelog.ui.io

import android.database.sqlite.SQLiteDatabase
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.GZIPInputStream

/** Reads the export with the platform SQLite, read-only, from a temporary copy. */
actual fun readShearwaterCloudExport(bytes: ByteArray): ShearwaterCloudExport {
    val file = File.createTempFile("shearwater-cloud", ".db")
    try {
        file.writeBytes(bytes)
        val db = SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val logs = db.rawQuery(ShearwaterCloudQueries.LOGS, null).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(ShearwaterCloudLog(c.getString(0), c.getString(1), (2..4).map { i -> c.getBlob(i)?.let(::unzip) }))
                    }
                }
            }
            val details = db.rawQuery(ShearwaterCloudQueries.DETAILS, null).use { c ->
                buildMap {
                    while (c.moveToNext()) {
                        val row = c.columnNames.withIndex().mapNotNull { (i, name) -> c.getString(i)?.let { name to it } }.toMap()
                        row[ShearwaterCloudQueries.DETAILS_KEY]?.let { put(it, row) }
                    }
                }
            }
            return ShearwaterCloudExport(logs, details)
        } finally {
            db.close()
        }
    } finally {
        file.delete()
    }
}

private fun unzip(blob: ByteArray): ByteArray =
    if (!ShearwaterCloudQueries.isGzipped(blob)) {
        blob
    } else {
        val offset = ShearwaterCloudQueries.GZIP_OFFSET
        GZIPInputStream(ByteArrayInputStream(blob, offset, blob.size - offset)).use { it.readBytes() }
    }
