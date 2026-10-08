package no.synth.divelog.ui.io

/** The bits of SQLite the Shearwater Cloud import and export need, on any platform. */
interface SqliteFile {
    /** Runs [sql] and hands each row to [onRow]. */
    fun query(sql: String, onRow: (SqliteRow) -> Unit)

    /** Runs [sql] with [args] bound in order: String, Long, Int, Double, ByteArray or null. */
    fun execute(sql: String, args: List<Any?> = emptyList())

    /** Runs [block] in one transaction. */
    fun transaction(block: () -> Unit)
}

interface SqliteRow {
    val columnCount: Int

    fun name(index: Int): String

    fun string(index: Int): String?

    fun bytes(index: Int): ByteArray?
}

/**
 * Opens a temporary database file holding [initial] (an empty one when null), runs [block]
 * on it, and returns the result together with the file's bytes afterwards.
 */
internal expect fun <T> withSqliteFile(initial: ByteArray?, block: (SqliteFile) -> T): Pair<T, ByteArray>

internal expect fun gzip(data: ByteArray): ByteArray

internal expect fun gunzip(data: ByteArray): ByteArray
