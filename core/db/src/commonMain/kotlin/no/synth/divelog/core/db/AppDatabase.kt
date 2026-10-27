package no.synth.divelog.core.db

import app.cash.sqldelight.Query
import app.cash.sqldelight.Transacter
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import no.synth.divelog.core.db.sql.DiveDatabase

/** Runs a query and returns its rows, the first [columns] values of each as text. */
fun interface RowQuery {
    fun rows(sql: String, columns: Int): List<List<String?>>
}

/** The app's [DiveDatabase], keeping its [driver] for statements SQLDelight cannot parse. */
class AppDatabase(private val driver: SqlDriver) : DiveDatabase by DiveDatabase(driver) {
    private val query = RowQuery { sql, columns ->
        driver.executeQuery(null, sql, { cursor ->
            val rows = mutableListOf<List<String?>>()
            while (cursor.next().value) rows += (0 until columns).map { cursor.getString(it) }
            QueryResult.Value(rows)
        }, 0).value
    }

    /**
     * Writes a consistent copy of the whole database, schema version included, to a new
     * file at [path], which must not exist or be empty. Must not run inside a transaction.
     */
    fun backupTo(path: String) {
        driver.execute(null, "VACUUM INTO ?", 1) { bindString(0, path) }
    }

    /** Each logbook table and its columns. */
    fun tableColumns(): Map<String, List<String>> = tableColumns(query)

    /** See [repairForeignKeys]; for a database whose foreign keys were not enforced. */
    fun repairForeignKeys(): Int = repairForeignKeys(query) { driver.execute(null, it, 0) }

    /**
     * Replaces every row of every logbook table in one transaction: empties them, then runs
     * [fill], which inserts the new rows through the statement runner it is given. Rows whose
     * foreign keys do not match roll it all back; they are checked before the commit, since a
     * commit that fails on them would leave the transaction open. Observed queries are told afterwards.
     */
    fun replaceAllRows(fill: (execute: (sql: String) -> Unit) -> Unit) {
        val tables = tableColumns().keys
        transaction {
            driver.execute(null, "PRAGMA defer_foreign_keys = ON", 0)
            tables.forEach { driver.execute(null, "DELETE FROM ${quoteName(it)}", 0) }
            fill { sql -> driver.execute(null, sql, 0) }
            check(query.rows("PRAGMA foreign_key_check", 1).isEmpty()) { "the backup's rows do not fit together" }
        }
        driver.notifyListeners(*tables.toTypedArray())
    }

    /** Emits each time a write to a logbook table commits. */
    fun changes(): Flow<Unit> = callbackFlow {
        val tables = tableColumns().keys.toTypedArray()
        val listener = Query.Listener { trySend(Unit) }
        driver.addListener(*tables, listener = listener)
        awaitClose { driver.removeListener(*tables, listener = listener) }
    }.conflate().flowOn(Dispatchers.Default)

    companion object {
        /** The schema version this app writes. */
        val schemaVersion: Long get() = DiveDatabase.Schema.version

        /**
         * Table and column names, one row each, in column order. Leaves out SQLite's own tables
         * and the one Android adds.
         */
        const val TABLE_COLUMNS_SQL =
            "SELECT m.name, p.name FROM sqlite_master m JOIN pragma_table_info(m.name) p " +
                "WHERE m.type = 'table' AND m.name NOT LIKE 'sqlite_%' AND m.name <> 'android_metadata' " +
                "ORDER BY m.name, p.cid"

        /**
         * Brings another database file from schema [version] up to [schemaVersion]; [execute]
         * runs one statement on that file.
         */
        fun migrate(version: Long, execute: (sql: String) -> Unit) {
            DiveDatabase.Schema.migrate(StatementDriver(execute), version, schemaVersion)
        }

        fun quoteName(name: String): String = "\"" + name.replace("\"", "\"\"") + "\""

        /** Each logbook table and its columns in order, read through [query]. */
        fun tableColumns(query: RowQuery): Map<String, List<String>> {
            val tables = linkedMapOf<String, MutableList<String>>()
            for ((table, column) in query.rows(TABLE_COLUMNS_SQL, 2)) {
                if (table != null && column != null) tables.getOrPut(table) { mutableListOf() } += column
            }
            return tables
        }

        /**
         * Gives each row whose parent row is gone what an enforced foreign key would have done when
         * the parent was deleted: the row goes (ON DELETE CASCADE) or its reference is cleared (SET
         * NULL). A database written without enforced foreign keys can hold such rows; [query] and
         * [execute] reach it. Returns how many rows were repaired.
         */
        fun repairForeignKeys(query: RowQuery, execute: (sql: String) -> Unit): Int {
            var repaired = 0
            // A deleted row can leave rows of its own without a parent, so look again until none are left.
            repeat(MAX_REPAIR_ROUNDS) {
                val broken = query.rows("PRAGMA foreign_key_check", 4)
                if (broken.isEmpty()) return repaired
                val keys = mutableMapOf<String, Map<String?, List<String?>>>()
                for ((table, rowid, _, fkid) in broken) {
                    if (table == null || rowid == null) continue
                    // foreign_key_list: id, seq, table, from, to, on_update, on_delete.
                    val key = keys.getOrPut(table) { query.rows("PRAGMA foreign_key_list(${quoteName(table)})", 7).associateBy { it[0] } }[fkid]
                    val column = key?.get(3)
                    execute(
                        if (column != null && key[6] == "SET NULL") {
                            "UPDATE ${quoteName(table)} SET ${quoteName(column)} = NULL WHERE rowid = $rowid"
                        } else {
                            "DELETE FROM ${quoteName(table)} WHERE rowid = $rowid"
                        },
                    )
                    repaired++
                }
            }
            error("Foreign keys still do not match after $MAX_REPAIR_ROUNDS rounds of repair")
        }

        private const val MAX_REPAIR_ROUNDS = 16
    }
}

/** Just enough of a driver for the schema migrations, which only run parameterless statements. */
private class StatementDriver(private val run: (String) -> Unit) : SqlDriver {
    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<Long> {
        require(parameters == 0) { "Migration statements take no parameters" }
        run(sql)
        return QueryResult.Value(0)
    }

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (SqlCursor) -> QueryResult<R>,
        parameters: Int,
        binders: (SqlPreparedStatement.() -> Unit)?,
    ): QueryResult<R> = throw UnsupportedOperationException("Migrations run no queries")

    override fun newTransaction(): QueryResult<Transacter.Transaction> =
        throw UnsupportedOperationException("Migrations run no transactions")

    override fun currentTransaction(): Transacter.Transaction? = null

    override fun addListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun removeListener(vararg queryKeys: String, listener: Query.Listener) = Unit

    override fun notifyListeners(vararg queryKeys: String) = Unit

    override fun close() = Unit
}
