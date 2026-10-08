package no.synth.divelog.core.db

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import no.synth.divelog.core.db.sql.DiveDatabase

/** Opens the app database, creating or migrating it, with foreign keys enforced. */
fun createDatabase(context: Context): DiveDatabase =
    DiveDatabase(
        AndroidSqliteDriver(
            schema = DiveDatabase.Schema,
            context = context,
            name = "divelog.db",
            callback = object : AndroidSqliteDriver.Callback(DiveDatabase.Schema) {
                override fun onConfigure(db: SupportSQLiteDatabase) {
                    super.onConfigure(db)
                    db.setForeignKeyConstraintsEnabled(true)
                }
            },
        ),
    )
