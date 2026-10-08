package no.synth.divelog.ui.io

import java.io.ByteArrayInputStream
import java.io.File
import java.sql.DriverManager
import java.util.zip.GZIPInputStream

/** Reads the export through the SQLite JDBC driver the desktop app already ships. */
actual fun readShearwaterCloudExport(bytes: ByteArray): ShearwaterCloudExport {
    val file = File.createTempFile("shearwater-cloud", ".db")
    try {
        file.writeBytes(bytes)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { c ->
            val logs = c.createStatement().use { st ->
                st.executeQuery(ShearwaterCloudQueries.LOGS).use { rs ->
                    buildList {
                        while (rs.next()) {
                            add(ShearwaterCloudLog(rs.getString(1), rs.getString(2), (3..5).map { rs.getBytes(it)?.let(::unzip) }))
                        }
                    }
                }
            }
            val details = c.createStatement().use { st ->
                st.executeQuery(ShearwaterCloudQueries.DETAILS).use { rs ->
                    val columns = (1..rs.metaData.columnCount).map { rs.metaData.getColumnName(it) }
                    buildMap {
                        while (rs.next()) {
                            val row = columns.withIndex().mapNotNull { (i, name) -> rs.getString(i + 1)?.let { name to it } }.toMap()
                            row[ShearwaterCloudQueries.DETAILS_KEY]?.let { put(it, row) }
                        }
                    }
                }
            }
            return ShearwaterCloudExport(logs, details)
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
