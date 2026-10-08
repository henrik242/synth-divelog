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

/** Reads a Shearwater Cloud export from the bytes of its database file. */
fun readShearwaterCloudExport(bytes: ByteArray): ShearwaterCloudExport = withSqliteFile(bytes) { db ->
    val logs = ArrayList<ShearwaterCloudLog>()
    db.query(ShearwaterCloudQueries.LOGS) { row ->
        val id = row.string(0) ?: return@query
        val format = row.string(1) ?: return@query
        logs += ShearwaterCloudLog(id, format, (2..4).map { i -> row.bytes(i)?.let(ShearwaterCloudQueries::unstore) })
    }
    val details = LinkedHashMap<String, Map<String, String>>()
    db.query(ShearwaterCloudQueries.DETAILS) { row ->
        val fields = (0 until row.columnCount).mapNotNull { i -> row.string(i)?.let { row.name(i) to it } }.toMap()
        fields[ShearwaterCloudQueries.DETAILS_KEY]?.let { details[it] = fields }
    }
    ShearwaterCloudExport(logs, details)
}.first

/** The export's queries and blob layout, shared by its reader and writer. */
internal object ShearwaterCloudQueries {
    const val LOGS = "SELECT log_id, format, data_bytes_1, data_bytes_2, data_bytes_3 FROM log_data"
    const val DETAILS = "SELECT * FROM dive_details"
    const val DETAILS_KEY = "DiveId"

    /** A stored blob is a little-endian length then a gzip stream, or plain bytes. */
    fun unstore(blob: ByteArray): ByteArray =
        if (blob.size > 6 && blob[4] == 0x1F.toByte() && blob[5] == 0x8B.toByte()) gunzip(blob.copyOfRange(4, blob.size)) else blob

    fun store(data: ByteArray): ByteArray {
        val n = data.size
        return byteArrayOf(n.toByte(), (n shr 8).toByte(), (n shr 16).toByte(), (n shr 24).toByte()) + gzip(data)
    }
}

private const val SQLITE_MAGIC = "SQLite format 3"
