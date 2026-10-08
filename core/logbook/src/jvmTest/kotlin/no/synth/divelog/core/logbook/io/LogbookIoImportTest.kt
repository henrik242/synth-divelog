package no.synth.divelog.core.logbook.io

import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyperDump
import no.synth.divelog.core.formats.ComputerEntry
import no.synth.divelog.core.formats.DiveEntry
import no.synth.divelog.core.formats.DiveLog
import no.synth.divelog.core.formats.SubsurfaceXml
import no.synth.divelog.core.formats.UddfFormat
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LogbookIoImportTest {
    private val container = AppContainer(createDatabase())
    private val io = LogbookIo(container)

    private val start = 1_700_000_000L
    private fun profile(depth: Int) = listOf(Sample(0, depthMm = 0), Sample(60, depthMm = depth), Sample(120, depthMm = 0))

    private fun deviceNames(diveId: Long) = container.dives.recordsForDive(diveId)
        .mapNotNull { r -> r.deviceId?.let { container.devices.get(it) }?.model }.sorted()

    @Test
    fun aFirstComputerWithoutAProfileIsKeptAlongsideTheOneWithIt() {
        val dive = DiveEntry(
            startEpochSeconds = start,
            durationSeconds = 2_400,
            computers = listOf(
                ComputerEntry(model = "Suunto HelO2", serial = "B2", maxDepthMm = 19_800),
                ComputerEntry(model = "Shearwater Petrel", serial = "A1", maxDepthMm = 20_000, samples = profile(20_000)),
            ),
        )
        io.importMessage(SubsurfaceXml().write(DiveLog(listOf(dive))))

        val stored = container.dives.allDives().single()
        assertEquals(listOf("HelO2", "Petrel"), deviceNames(stored.id))
        val primary = container.dives.record(requireNotNull(stored.primaryComputerRecordId))
        assertEquals(3, container.dives.samplesForRecord(requireNotNull(primary).id).size)
    }

    @Test
    fun aMergedCopyDoesNotAttachAComputerTheDiveAlreadyHas() {
        // Downloaded from the HelO2.
        val helo2 = container.devices.getOrCreate(Device(vendor = "Suunto", model = "HelO2", serial = "B2"))
        container.dives.import(
            IncomingDive(
                deviceId = helo2,
                startEpochSeconds = start,
                utcOffsetSeconds = 0,
                durationSeconds = 2_400,
                rawData = byteArrayOf(1),
                rawFormatId = "test",
                fingerprint = "helo2-1",
            ),
        ) { false }
        // A logbook with the Petrel's profile and the HelO2 as a second computer.
        val dive = DiveEntry(
            startEpochSeconds = start,
            durationSeconds = 2_400,
            computers = listOf(
                ComputerEntry(model = "Shearwater Petrel", serial = "A1", samples = profile(20_000)),
                ComputerEntry(model = "Suunto HelO2", serial = "B2", samples = profile(19_800)),
            ),
        )
        val message = io.importMessage(SubsurfaceXml().write(DiveLog(listOf(dive))))

        assertTrue(message.contains("merged 1"), message)
        assertEquals(listOf("HelO2", "Petrel"), deviceNames(container.dives.allDives().single().id))
    }

    @Test
    fun aZonedCopyOfAWallClockDiveExportsWithoutAnOffsetRecordStart() {
        // Downloaded: wall clock, zone unknown.
        val petrel = container.devices.getOrCreate(Device(vendor = "Shearwater", model = "Petrel", serial = "A1"))
        container.dives.import(
            IncomingDive(
                deviceId = petrel,
                startEpochSeconds = start,
                utcOffsetSeconds = 0,
                durationSeconds = 2_400,
                rawData = byteArrayOf(1),
                rawFormatId = "test",
                fingerprint = "petrel-1",
            ),
        ) { false }
        // The same dive from another computer in a UDDF that knows the zone (+02:00).
        val zoned = DiveEntry(
            startEpochSeconds = start - 7_200,
            utcOffsetSeconds = 7_200,
            durationSeconds = 2_400,
            computers = listOf(ComputerEntry(model = "Suunto HelO2", serial = "B2", samples = profile(19_800))),
        )
        io.importMessage(UddfFormat().write(DiveLog(listOf(zoned))))

        val exported = SubsurfaceXml().read(io.exportAll(SubsurfaceXml())).dives.single()
        assertEquals(2, exported.computers.size)
        assertTrue(exported.computers.all { it.startEpochSeconds == null }, exported.computers.toString())
    }

    @Test
    fun reparseReportsTheRecordsItCouldNotParse() {
        val vyper = container.devices.getOrCreate(Device(vendor = "Suunto", model = "Vyper", serial = "V1"))
        val created = container.dives.importAsNewDive(
            IncomingDive(
                deviceId = vyper,
                startEpochSeconds = start,
                utcOffsetSeconds = 0,
                durationSeconds = 2_400,
                rawData = byteArrayOf(1, 2), // shorter than a Vyper record header
                rawFormatId = SuuntoVyperDump.FORMAT_ID,
                fingerprint = "vyper-1",
            ),
        )
        val result = io.reparseAllWithFailures()
        assertEquals(0, result.reparsed)
        assertEquals(listOf(created.recordId), result.failures.map { it.recordId })
        assertEquals(0, io.reparseAll())
    }

    @Test
    fun exportKeepsEveryDivesRecordsTanksAndPeople() {
        val dive = DiveEntry(
            startEpochSeconds = start,
            durationSeconds = 2_400,
            notes = "Notes",
            buddies = listOf("Alex"),
            tags = listOf("wreck"),
            computers = listOf(ComputerEntry(model = "Shearwater Petrel", serial = "A1", samples = profile(20_000))),
        )
        val other = dive.copy(startEpochSeconds = start + 86_400, buddies = listOf("Kari"), tags = emptyList())
        io.importMessage(SubsurfaceXml().write(DiveLog(listOf(dive, other))))

        val exported = SubsurfaceXml().read(io.exportAll(SubsurfaceXml())).dives.sortedBy { it.startEpochSeconds }
        assertEquals(listOf(listOf("Alex"), listOf("Kari")), exported.map { it.buddies })
        assertEquals(listOf(listOf("wreck"), emptyList()), exported.map { it.tags })
        assertEquals(listOf(3, 3), exported.map { it.computers.single().samples.size })
        assertNull(exported.first().computers.single().startEpochSeconds)
    }
}
