package no.synth.divelog.ui.io

import java.io.ByteArrayOutputStream
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Through the SQLite JDBC driver the desktop app already ships. */
internal actual fun <T> withSqliteFile(initial: ByteArray?, block: (SqliteFile) -> T): Pair<T, ByteArray> {
    val file = File.createTempFile("synth-sqlite", ".db")
    try {
        if (initial != null) file.writeBytes(initial) else file.delete() // SQLite creates it
        val result = DriverManager.getConnection("jdbc:sqlite:${file.path}").use { block(JdbcFile(it)) }
        return result to file.readBytes()
    } finally {
        file.delete()
    }
}

private class JdbcFile(private val c: Connection) : SqliteFile {
    override fun query(sql: String, onRow: (SqliteRow) -> Unit) {
        c.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val row = JdbcRow(rs)
                while (rs.next()) onRow(row)
            }
        }
    }

    override fun execute(sql: String, args: List<Any?>) {
        c.prepareStatement(sql).use { st ->
            args.forEachIndexed { i, a -> bind(st, i + 1, a) }
            st.execute()
        }
    }

    override fun transaction(block: () -> Unit) {
        c.autoCommit = false
        try {
            block()
            c.commit()
        } catch (e: Throwable) {
            c.rollback()
            throw e
        } finally {
            c.autoCommit = true
        }
    }

    private fun bind(st: PreparedStatement, index: Int, value: Any?) = when (value) {
        null -> st.setObject(index, null)
        is String -> st.setString(index, value)
        is Long -> st.setLong(index, value)
        is Int -> st.setLong(index, value.toLong())
        is Double -> st.setDouble(index, value)
        is ByteArray -> st.setBytes(index, value)
        else -> error("Cannot bind ${value::class.simpleName}")
    }
}

private class JdbcRow(private val rs: ResultSet) : SqliteRow {
    override val columnCount: Int get() = rs.metaData.columnCount

    override fun name(index: Int): String = rs.metaData.getColumnName(index + 1)

    override fun string(index: Int): String? = rs.getString(index + 1)

    override fun bytes(index: Int): ByteArray? = rs.getBytes(index + 1)
}

internal actual fun gzip(data: ByteArray): ByteArray =
    ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

internal actual fun gunzip(data: ByteArray): ByteArray = GZIPInputStream(data.inputStream()).use { it.readBytes() }
