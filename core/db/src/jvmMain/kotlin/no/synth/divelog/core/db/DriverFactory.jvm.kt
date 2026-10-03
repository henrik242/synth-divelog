// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import no.synth.divelog.core.db.sql.DiveDatabase

/**
 * JVM driver. A null [databasePath] opens an in-memory database (used by tests);
 * a path opens or creates a file-backed one.
 */
actual class DriverFactory(private val databasePath: String? = null) {
    actual fun createDriver(): SqlDriver {
        // Decide before opening: opening a file URL creates the file.
        val needsSchema = databasePath == null || !java.io.File(databasePath).exists()
        val url = if (databasePath == null) JdbcSqliteDriver.IN_MEMORY else "jdbc:sqlite:$databasePath"
        val driver = JdbcSqliteDriver(url)
        if (needsSchema) {
            DiveDatabase.Schema.create(driver)
        }
        driver.execute(null, "PRAGMA foreign_keys=ON", 0)
        return driver
    }
}
