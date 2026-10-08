package no.synth.divelog.ui.io

/**
 * A Shearwater Cloud database export ("Export Database"), as read by the platform: each
 * dive's stored log ([logs]) and its logbook fields ([details], keyed by dive id).
 */
class ShearwaterCloudExport(
    val logs: List<ShearwaterCloudLog>,
    val details: Map<String, Map<String, String>>,
)

/**
 * One stored dive log. [format] names how [blobs] hold it ("sw-pnf", "sw-predator",
 * "sw-clouddb", "uddf"); blobs are already unzipped.
 */
class ShearwaterCloudLog(val id: String, val format: String, val blobs: List<ByteArray?>)

/** Whether [bytes] are an SQLite database file, as a Shearwater Cloud export is. */
fun isSqliteFile(bytes: ByteArray): Boolean =
    bytes.size >= SQLITE_MAGIC.length && bytes.copyOfRange(0, SQLITE_MAGIC.length).decodeToString() == SQLITE_MAGIC

/**
 * Reads a Shearwater Cloud export from the bytes of its database file. Throws
 * [UnsupportedOperationException] where the platform has no reader.
 */
expect fun readShearwaterCloudExport(bytes: ByteArray): ShearwaterCloudExport

/** The export's queries, shared by the platform readers. */
internal object ShearwaterCloudQueries {
    const val LOGS = "SELECT log_id, format, data_bytes_1, data_bytes_2, data_bytes_3 FROM log_data"
    const val DETAILS = "SELECT * FROM dive_details"
    const val DETAILS_KEY = "DiveId"

    /** A stored blob: a little-endian length, then a gzip stream; or plain bytes. */
    fun isGzipped(blob: ByteArray) = blob.size > 6 && blob[4] == 0x1F.toByte() && blob[5] == 0x8B.toByte()

    const val GZIP_OFFSET = 4
}

private const val SQLITE_MAGIC = "SQLite format 3"
