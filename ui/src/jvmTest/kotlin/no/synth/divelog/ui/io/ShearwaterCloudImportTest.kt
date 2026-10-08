package no.synth.divelog.ui.io

import no.synth.divelog.core.db.DriverFactory
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.model.ComputerNames
import no.synth.divelog.ui.AppContainer
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.sql.DriverManager
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A made-up Shearwater Cloud export, in the layout the real ones use. */
class ShearwaterCloudImportTest {
    private val container = AppContainer(DriverFactory().createDatabase())
    private val io = LogbookIo(container)

    /** A stored blob: little-endian length, then gzip. */
    private fun stored(text: String): ByteArray {
        val raw = text.encodeToByteArray()
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(raw) } }.toByteArray()
        return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(raw.size).array() + gz
    }

    private fun exportFile(): ByteArray {
        val file = File.createTempFile("sw-test", ".db")
        try {
            DriverManager.getConnection("jdbc:sqlite:${file.path}").use { c ->
                c.createStatement().use {
                    it.execute("CREATE TABLE log_data (log_id varchar, format varchar, data_bytes_1 BLOB, data_bytes_2 BLOB, data_bytes_3 BLOB)")
                    it.execute(
                        "CREATE TABLE dive_details (DiveId varchar, SerialNumber varchar, DiveNumber varchar, Site varchar, Location varchar, " +
                            "Buddy varchar, Notes varchar, TankSize varchar, TankProfileData varchar)",
                    )
                }
                val header = """{"number":515,"startDate":"1420981068","imperialUnits":0,"FooterDiveTimeInSeconds":1500,"maxDepthFloat":18.0}"""
                val samples = (0..150).joinToString(",", "[", "]") { i ->
                    val depth = if (i in 1..149) 18.0 else 0.0
                    """{"currentTime":${i * 10_000},"currentDepth":$depth,"waterTemp":8,"fractionO2":32.0,"fractionHe":0.0,"currentNdl":40,"CNSPercent":3}"""
                }
                c.prepareStatement("INSERT INTO log_data VALUES (?, ?, ?, ?, NULL)").use { st ->
                    st.setString(1, "dive-1"); st.setString(2, "sw-clouddb")
                    st.setBytes(3, stored(header)); st.setBytes(4, stored(samples))
                    st.execute()
                }
                val tanks = """{"GasProfiles":[{"O2Percent":32,"HePercent":0}],""" +
                    """"TankData":[{"StartPressurePSI":"3000","EndPressurePSI":"1000","GasProfile":{"O2Percent":32,"HePercent":0}}]}"""
                c.prepareStatement("INSERT INTO dive_details VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)").use { st ->
                    listOf("dive-1", "1a2b3c4d", "515", "The Wall", "Drøbak", "Alex, Sam", "\n\n\n", "0.012", tanks)
                        .forEachIndexed { i, v -> st.setString(i + 1, v) }
                    st.execute()
                }
            }
            return file.readBytes()
        } finally {
            file.delete()
        }
    }

    @Test
    fun importsADecodedDiveWithItsLogbookFields() {
        val message = io.importFileMessage(exportFile())
        assertTrue(message.startsWith("Imported 1,"), message)

        val dive = container.dives.allDives().single()
        assertEquals(515, dive.number)
        assertEquals(1_420_981_068, dive.startEpochSeconds)
        assertEquals(1_500, dive.durationSeconds)
        assertEquals(18_000, dive.maxDepthMm)
        assertEquals(null, dive.notes) // blank lines are no notes
        assertEquals("The Wall", dive.siteId?.let { container.sites.site(it) }?.name)
        assertEquals(listOf("Alex", "Sam"), container.buddies.buddiesForDive(dive.id).map { it.name })

        val record = container.dives.recordsForDive(dive.id).single()
        val samples = container.dives.samplesForRecord(record.id)
        assertEquals(10, samples[1].timeOffsetSeconds) // milliseconds in the export
        val device = requireNotNull(record.deviceId?.let { container.devices.get(it) })
        assertEquals("1A2B3C4D", device.serial)
        assertEquals("Shearwater", ComputerNames.fullName(device))

        val tank = container.gases.tanksForDive(dive.id).single()
        assertEquals(12_000, tank.volumeMl)
        assertEquals(206_843, tank.startPressureMbar) // 3000 psi
        assertEquals(68_948, tank.endPressureMbar)
        assertEquals(320, tank.gasMixId?.let { container.gases.gasMix(it) }?.o2Permille)
    }

    @Test
    fun aCopyNamingNoComputerFoldsIntoTheLoggedDive() {
        io.importFileMessage(exportFile())
        // The same dive from a logbook that does not say which computer logged it.
        val uddf = """<uddf version="3.2.1"><profiledata><repetitiongroup><dive>
            <informationbeforedive><datetime>2015-01-11T12:57:48</datetime></informationbeforedive>
            <informationafterdive><diveduration>1500.0</diveduration><notes><para>Nice wall</para></notes></informationafterdive>
            </dive></repetitiongroup></profiledata></uddf>"""
        val message = io.importMessage(uddf)
        assertTrue(message.contains("skipped 1"), message)
        val dive = container.dives.allDives().single()
        assertEquals("Nice wall", dive.notes)
        assertEquals(1, container.dives.recordsForDive(dive.id).size)
    }
}
