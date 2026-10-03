// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.db

import app.cash.sqldelight.db.SqlDriver
import no.synth.divelog.core.db.sql.DiveDatabase

/**
 * Creates a platform SQL driver with the schema applied and foreign-key
 * enforcement on. Each platform supplies its own constructor.
 */
expect class DriverFactory {
    fun createDriver(): SqlDriver
}

/** Wraps a ready driver in the generated database. */
fun DriverFactory.createDatabase(): DiveDatabase = DiveDatabase(createDriver())
