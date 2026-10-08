package no.synth.divelog.core.logbook.io

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlCursor
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.formats.ComputerEntry
import no.synth.divelog.core.formats.DiveEntry
import no.synth.divelog.core.formats.DiveLog
import no.synth.divelog.core.formats.SiteRef
import no.synth.divelog.core.formats.SubsurfaceXml
import no.synth.divelog.core.formats.TankEntry
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.model.Sample
import kotlin.test.Test
import kotlin.test.assertEquals

/** An export reads each table once, however many dives the log holds. */
class ExportQueryCountTest {
    private class CountingDriver(private val driver: SqlDriver) : SqlDriver by driver {
        var queries = 0
        override fun <R> executeQuery(
            identifier: Int?,
            sql: String,
            mapper: (SqlCursor) -> QueryResult<R>,
            parameters: Int,
            binders: (SqlPreparedStatement.() -> Unit)?,
        ): QueryResult<R> {
            queries++
            return driver.executeQuery(identifier, sql, mapper, parameters, binders)
        }
    }

    private fun queriesToExport(dives: Int): Int {
        val driver = CountingDriver(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { DiveDatabase.Schema.create(it) })
        val io = LogbookIo(AppContainer(DiveDatabase(driver)))
        val log = DiveLog(
            (0 until dives).map { i ->
                DiveEntry(
                    startEpochSeconds = 1_700_000_000L + i * 86_400,
                    durationSeconds = 2_400,
                    site = SiteRef("Site $i", country = "Norway", place = "Gulen"),
                    buddies = listOf("Alex"),
                    tags = listOf("wreck"),
                    tanks = listOf(TankEntry(0, volumeMl = 12_000, o2Permille = 320, hePermille = 0)),
                    computers = listOf(
                        ComputerEntry(model = "Shearwater Petrel", serial = "A1", samples = listOf(Sample(0, depthMm = 0), Sample(60, depthMm = 10_000))),
                        ComputerEntry(model = "Suunto HelO2", serial = "B2", samples = listOf(Sample(0, depthMm = 0))),
                    ),
                )
            },
        )
        io.importMessage(SubsurfaceXml().write(log))
        driver.queries = 0
        io.exportAll(SubsurfaceXml())
        return driver.queries
    }

    @Test
    fun exportQueriesDoNotGrowWithTheLog() {
        // Dives, records, samples, tank pressures, events, devices, gas mixes, tanks,
        // buddies, tags, sites, places and countries: one query each.
        assertEquals(13, queriesToExport(1))
        assertEquals(13, queriesToExport(50))
    }
}
