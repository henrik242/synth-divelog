package no.synth.divelog.core.db

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import no.synth.divelog.core.db.sql.DiveDatabase

/**
 * Opens the database file at [databasePath], creating or migrating it; null opens an
 * in-memory database (tests).
 *
 * Unlike the Android and iOS drivers, the JDBC driver does not track the schema version,
 * so it is kept by hand in PRAGMA user_version. A database with version 0 and no tables
 * (new, or an empty file) is created; an older one is migrated. Either way the schema
 * change and the version stamp commit together.
 */
fun createDatabase(databasePath: String? = null): DiveDatabase {
    val driver = JdbcSqliteDriver(if (databasePath == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$databasePath")
    val schema = DiveDatabase.Schema
    val current = userVersion(driver)
    if (current < schema.version) {
        object : TransacterImpl(driver) {}.transaction {
            if (current == 0L && !hasTable(driver, "dive")) {
                schema.create(driver)
            } else {
                schema.migrate(driver, current, schema.version)
            }
            // PRAGMA does not accept bound parameters, so the version is inlined.
            driver.execute(null, "PRAGMA user_version = ${schema.version}", 0)
        }
    }
    // Outside the transaction: SQLite ignores this pragma inside one.
    driver.execute(null, "PRAGMA foreign_keys=ON", 0)
    return DiveDatabase(driver)
}

private fun userVersion(driver: SqlDriver): Long =
    driver.executeQuery(
        identifier = null,
        sql = "PRAGMA user_version",
        mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
        parameters = 0,
    ).value

private fun hasTable(driver: SqlDriver, name: String): Boolean =
    driver.executeQuery(
        identifier = null,
        sql = "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name = ?",
        mapper = { cursor -> QueryResult.Value(cursor.next().value && (cursor.getLong(0) ?: 0L) > 0) },
        parameters = 1,
    ) { bindString(0, name) }.value
