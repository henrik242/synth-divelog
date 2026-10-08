package no.synth.divelog.core.db

import app.cash.sqldelight.driver.native.NativeSqliteDriver
import no.synth.divelog.core.db.sql.DiveDatabase

/** Opens the app database, creating or migrating it, with foreign keys enforced. */
fun createDatabase(): DiveDatabase =
    DiveDatabase(
        NativeSqliteDriver(
            schema = DiveDatabase.Schema,
            name = "divelog.db",
            onConfiguration = { config ->
                config.copy(
                    extendedConfig = config.extendedConfig.copy(foreignKeyConstraints = true),
                )
            },
        ),
    )
