package no.synth.divelog.core.db

import no.synth.divelog.core.model.Device
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DeletedDiveTest {
    private val db = testDatabase()
    private val dives = DiveRepository(db)
    private val devices = DeviceRepository(db)
    private val petrel = devices.add(Device(vendor = "Shearwater", model = "Petrel"))

    @Test
    fun aDeletedDiveIsRememberedUntilItIsImportedAgain() {
        val diveId = dives.importAsNewDive(incoming(deviceId = petrel, fingerprint = "a")).diveId
        dives.deleteDive(diveId)
        assertTrue(dives.isDeleted(petrel, "a"))
        // Nothing is stored any more, so it would import as new; the download asks first.
        assertEquals(ImportDecision.NewDive, dives.classify(incoming(deviceId = petrel, fingerprint = "a")))

        dives.importAsNewDive(incoming(deviceId = petrel, fingerprint = "a"))
        assertFalse(dives.isDeleted(petrel, "a"))
    }

    @Test
    fun anIncrementalDownloadStopsAtTheNewestDeletedDive() {
        dives.importAsNewDive(incoming(deviceId = petrel, start = 1_000_000, fingerprint = "old"))
        val newest = dives.importAsNewDive(incoming(deviceId = petrel, start = 2_000_000, fingerprint = "new")).diveId
        dives.deleteDive(newest)
        assertEquals("new", dives.newestFingerprint(petrel))
    }

    @Test
    fun aDeviceMergeKeepsTheDeletedDives() {
        val other = devices.add(Device(vendor = "Shearwater", model = "Petrel"))
        dives.deleteDive(dives.importAsNewDive(incoming(deviceId = other, fingerprint = "a")).diveId)
        dives.deleteDive(dives.importAsNewDive(incoming(deviceId = petrel, start = 5_000_000, fingerprint = "a")).diveId)
        devices.merge(other, petrel)
        assertTrue(dives.isDeleted(petrel, "a"))
    }

    @Test
    fun deletingSeveralDivesRemembersEach() {
        val a = dives.importAsNewDive(incoming(deviceId = petrel, start = 1_000_000, fingerprint = "a")).diveId
        val b = dives.importAsNewDive(incoming(deviceId = petrel, start = 2_000_000, fingerprint = "b")).diveId
        dives.deleteDives(listOf(a, b))
        assertTrue(dives.isDeleted(petrel, "a"))
        assertTrue(dives.isDeleted(petrel, "b"))
    }

    @Test
    fun aJoinedDiveRemembersItsPartsOnceDeleted() {
        val first = dives.importAsNewDive(incoming(deviceId = petrel, start = 1_000_000, duration = 600, fingerprint = "a")).diveId
        val second = dives.importAsNewDive(incoming(deviceId = petrel, start = 1_000_900, duration = 600, fingerprint = "b")).diveId
        val joined = dives.mergeDives(listOf(first, second))
        // While the joined dive is there, a download of either part finds it stored.
        assertTrue(dives.classify(incoming(deviceId = petrel, start = 1_000_000, duration = 600, fingerprint = "a")) is ImportDecision.Duplicate)
        assertEquals("b", dives.newestFingerprint(petrel))

        dives.deleteDive(joined)
        assertTrue(dives.isDeleted(petrel, "a"))
        assertTrue(dives.isDeleted(petrel, "b"))
        assertEquals("b", dives.newestFingerprint(petrel))
    }

    @Test
    fun refreshReplacesTheRawDataAndKeepsUserEdits() {
        val created = dives.importAsNewDive(
            incoming(deviceId = petrel, maxDepthMm = 10_000, rawData = byteArrayOf(1), samples = listOf(sampleAt(0, 0))),
        )
        dives.updateDive(assertNotNull(dives.getDive(created.diveId)).copy(notes = "kept"))

        dives.refreshRecord(
            created.recordId,
            incoming(
                deviceId = petrel,
                maxDepthMm = 20_000,
                rawData = byteArrayOf(9, 9),
                samples = listOf(sampleAt(0, 0), sampleAt(10, 20_000)),
            ),
        )

        assertContentEquals(byteArrayOf(9, 9), dives.rawData(created.recordId))
        assertEquals(2, dives.samplesForRecord(created.recordId).size)
        val dive = assertNotNull(dives.getDive(created.diveId))
        assertEquals(20_000, dive.maxDepthMm)
        assertEquals("kept", dive.notes)
    }
}
