package no.synth.divelog.core.db

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import no.synth.divelog.core.db.sql.DiveDatabase

actual class DriverFactory {
    actual fun createDriver(): SqlDriver =
        NativeSqliteDriver(
            schema = DiveDatabase.Schema,
            name = "divelog.db",
            onConfiguration = { config ->
                config.copy(
                    extendedConfig = config.extendedConfig.copy(foreignKeyConstraints = true),
                )
            },
        )
}
