package no.synth.divelog.core.db

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import no.synth.divelog.core.db.sql.DiveDatabase

/**
 * JVM driver. A null [databasePath] opens an in-memory database (used by tests);
 * a path opens or creates a file-backed one.
 *
 * Unlike the Android and iOS drivers, the JDBC driver does not track the schema
 * version, so we do it by hand via PRAGMA user_version: create and stamp a fresh
 * database, and migrate an older one up to the current schema version.
 */
actual class DriverFactory(private val databasePath: String? = null) {
    actual fun createDriver(): SqlDriver {
        // Decide before opening: opening a file URL creates the file.
        val isFresh = databasePath == null || !java.io.File(databasePath).exists()
        val url = if (databasePath == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$databasePath"
        val driver = JdbcSqliteDriver(url)
        val schema = DiveDatabase.Schema
        if (isFresh) {
            schema.create(driver)
            setUserVersion(driver, schema.version)
        } else {
            val current = userVersion(driver)
            if (current < schema.version) {
                schema.migrate(driver, current, schema.version)
                setUserVersion(driver, schema.version)
            }
        }
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        return driver
    }

    private fun userVersion(driver: SqlDriver): Long =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA user_version",
            mapper = { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
            },
            parameters = 0,
        ).value

    // PRAGMA does not accept bound parameters, so the version is inlined.
    private fun setUserVersion(driver: SqlDriver, version: Long) {
        driver.execute(null, "PRAGMA user_version = $version", 0)
    }
}
