package no.synth.divelog.core.logbook.io

import no.synth.divelog.core.formats.ComputerEntry
import no.synth.divelog.core.formats.DiveEntry
import no.synth.divelog.core.formats.DiveLog
import no.synth.divelog.core.formats.SiteRef
import no.synth.divelog.core.formats.TankEntry
import no.synth.divelog.core.model.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ShearwaterCloudWriterTest {
    private val dive = DiveEntry(
        number = 42,
        startEpochSeconds = 1_719_756_300, // 2024-06-30 14:05:00 UTC
        utcOffsetSeconds = 7_200,
        durationSeconds = 2_400,
        maxDepthMm = 18_400,
        notes = "Kelp and a seal",
        site = SiteRef(name = "The Wall", place = "Drøbak"),
        buddies = listOf("Alex", "Sam"),
        tanks = listOf(
            TankEntry(index = 0, volumeMl = 12_000, startPressureMbar = 200_000, endPressureMbar = 60_000, o2Permille = 320, hePermille = 0),
        ),
        computers = listOf(
            ComputerEntry(
                model = "Shearwater Petrel",
                serial = "1a2b3c4d",
                samples = listOf(Sample(0, 0), Sample(60, 10_000, 285_150), Sample(1_200, 18_400, 283_150), Sample(2_400, 0)),
            ),
        ),
    )

    private val bytes = ShearwaterCloudWriter.write(DiveLog(listOf(dive)), nowEpochSeconds = 1_760_000_000)

    private fun rows(sql: String): List<Map<String, String?>> = withSqliteFile(bytes) { db ->
        val out = ArrayList<Map<String, String?>>()
        db.query(sql) { row -> out += (0 until row.columnCount).associate { row.name(it) to row.string(it) } }
        out
    }.first

    @Test
    fun isADatabaseInShearwaterCloudsLayout() {
        assertTrue(isSqliteFile(bytes))
        assertEquals("0", rows("PRAGMA user_version").single().values.single())
        assertEquals(ShearwaterCloudSchema.VERSIONS.size, rows("SELECT * FROM SWC_TableVersion").size)

        val log = rows("SELECT log_id, format, table_version FROM log_data").single()
        assertEquals("uddf", log["format"])
        assertEquals("3", log["table_version"])
        assertTrue(log["log_id"].orEmpty().endsWith("-1719756300"))

        val sync = rows("SELECT Id, FieldTimeStampJson FROM SyncV3MetadataDiveDetail").single()
        assertEquals(log["log_id"], sync["Id"])
        assertTrue(sync["FieldTimeStampJson"].orEmpty().contains("\"Notes\":1760000000000"))
    }

    @Test
    fun writesTheLogbookFieldsInShearwaterUnits() {
        val d = rows("SELECT * FROM dive_details").single()
        assertEquals("2024-06-30 16:05:00", d["DiveDate"]) // local wall clock
        assertEquals("06/30/2024 16:05:00", d["DateAndTime"])
        assertEquals("18.4", d["Depth"])
        assertEquals("1A2B3C4D", d["SerialNumber"])
        assertEquals("2901", d["Tank1PressureStart"]) // psi
        assertEquals("870", d["Tank1PressureEnd"])
        assertEquals("0.012", d["TankSize"]) // m³
        assertEquals("Alex, Sam", d["Buddy"])
        assertEquals("Drøbak", d["Location"])
        assertNull(d["Tank2PressureStart"])
    }

    @Test
    fun readsBackAsTheSameDive() {
        val read = ShearwaterCloudDives.read(readShearwaterCloudExport(bytes))
        assertEquals(0, read.unreadable)
        val back = read.dives.single().entry
        assertEquals(dive.number, back.number)
        // The UDDF carries the zone, so the instant and offset both come back.
        assertEquals(dive.startEpochSeconds, back.startEpochSeconds)
        assertEquals(dive.utcOffsetSeconds, back.utcOffsetSeconds)
        assertEquals(dive.durationSeconds, back.durationSeconds)
        assertEquals(dive.maxDepthMm, back.maxDepthMm)
        assertEquals(dive.notes, back.notes)
        assertEquals(dive.buddies, back.buddies)
        assertEquals("The Wall", back.site?.name)
        assertEquals("Drøbak", back.site?.place)
        assertEquals("1A2B3C4D", back.computers.single().serial?.uppercase())
        assertEquals(4, back.computers.single().samples.size)
        val tank = back.tanks.single()
        assertEquals(320, tank.o2Permille)
        assertEquals(12_000, tank.volumeMl)
    }

    @Test
    fun writesEachComputersProfileAsItsOwnDive() {
        val second = ComputerEntry(model = "Suunto HelO2", samples = listOf(Sample(0, 0), Sample(60, 9_000), Sample(120, 0)))
        val sw = ShearwaterCloudWriter.write(DiveLog(listOf(dive.copy(computers = dive.computers + second))), nowEpochSeconds = 0)
        val read = ShearwaterCloudDives.read(readShearwaterCloudExport(sw))
        assertEquals(listOf(4, 3), read.dives.map { it.entry.computers.single().samples.size })
    }

    @Test
    fun leavesOutSerialsOfOtherVendors() {
        val other = dive.copy(computers = listOf(ComputerEntry(model = "Suunto HelO2", serial = "34201234")))
        val sw = ShearwaterCloudWriter.write(DiveLog(listOf(other)), nowEpochSeconds = 0)
        val serials = withSqliteFile(sw) { db ->
            val out = ArrayList<String?>()
            db.query("SELECT SerialNumber FROM dive_details") { out += it.string(0) }
            out
        }.first
        assertEquals(listOf<String?>(null), serials)
    }
}
