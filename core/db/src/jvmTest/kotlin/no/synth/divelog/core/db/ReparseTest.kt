package no.synth.divelog.core.db

import no.synth.divelog.core.model.Device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class ReparseTest {
    private val db = testDatabase()
    private val dives = DiveRepository(db)
    private val devices = DeviceRepository(db)

    @Test
    fun reparseReplacesSamplesAndSummaryButKeepsUserEdits() {
        val device = devices.add(Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "AA:BB:CC"))

        // Import a dive as first parsed: wrong max depth and two samples.
        val created = dives.import(
            incoming(
                deviceId = device,
                maxDepthMm = 99_000,
                fingerprint = "fp-1",
                samples = listOf(sampleAt(0, 0), sampleAt(10, 5_000)),
            ),
        ) { false }
        val diveId = (created as ImportResult.CreatedDive).diveId
        val recordId = created.recordId

        // User edits the dive number and notes.
        dives.updateDive(assertNotNull(dives.getDive(diveId)).copy(number = 500, notes = "my dive"))

        // A parser fix re-parses the same raw data: corrected depth and three samples.
        dives.reparseRecord(
            recordId,
            incoming(
                deviceId = device,
                maxDepthMm = 20_000,
                fingerprint = "fp-1",
                samples = listOf(sampleAt(0, 0), sampleAt(10, 10_000), sampleAt(20, 20_000)),
            ),
        )

        val dive = assertNotNull(dives.getDive(diveId))
        assertEquals(20_000, dive.maxDepthMm, "primary record's new depth should reach the dive")
        assertEquals(15_000, dive.meanDepthMm)
        assertEquals(500, dive.number, "user-entered dive number must be kept")
        assertEquals("my dive", dive.notes, "user notes must be kept")
        assertEquals(3, dives.samplesForRecord(recordId).size, "samples should be replaced, not appended")
    }
}
