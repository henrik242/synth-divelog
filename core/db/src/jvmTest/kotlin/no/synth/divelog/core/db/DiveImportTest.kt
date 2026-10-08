package no.synth.divelog.core.db

import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.EventType
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class DiveImportTest {
    private val db = testDatabase()
    private val dives = DiveRepository(db)
    private val devices = DeviceRepository(db)

    private fun predator() = devices.add(Device(vendor = "Shearwater", model = "Predator", bluetoothAddress = "00:11:22"))
    private fun petrel() = devices.add(Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "AA:BB:CC"))

    @Test
    fun newDiveGetsSummaryAndPrimaryRecord() {
        val result = dives.import(incoming(deviceId = predator(), maxDepthMm = 42_000)) { false }
        val created = assertIs<ImportResult.CreatedDive>(result)

        val dive = dives.getDive(created.diveId)!!
        assertEquals(42_000, dive.maxDepthMm)
        assertEquals(created.recordId, dive.primaryComputerRecordId)
        assertEquals(1, dive.number)
        assertEquals(1, dives.recordsForDive(created.diveId).size)
    }

    @Test
    fun sameDeviceAndFingerprintIsDuplicate() {
        val dev = predator()
        val first = dives.import(incoming(deviceId = dev, fingerprint = "fp1")) { false }
        val firstRecord = assertIs<ImportResult.CreatedDive>(first).recordId

        val decision = dives.classify(incoming(deviceId = dev, fingerprint = "fp1"))
        assertEquals(ImportDecision.Duplicate(firstRecord), decision)

        val second = dives.import(incoming(deviceId = dev, fingerprint = "fp1")) { false }
        assertEquals(ImportResult.SkippedDuplicate(firstRecord), second)
        assertEquals(1, dives.allDives().size)
    }

    @Test
    fun overlappingDiveFromOtherComputerMergesWhenConfirmed() {
        val first = dives.import(incoming(deviceId = predator(), start = 1_000, duration = 3_600)) { false }
        val diveId = assertIs<ImportResult.CreatedDive>(first).diveId

        // Petrel worn on the same dive, overlapping time range, different fingerprint.
        val overlap = incoming(deviceId = petrel(), start = 1_120, duration = 3_500, fingerprint = "petrel-1")
        assertEquals(ImportDecision.MergeCandidate(diveId), dives.classify(overlap))

        val merged = assertIs<ImportResult.AttachedToDive>(dives.import(overlap) { candidate -> candidate == diveId })
        assertEquals(diveId, merged.diveId)

        assertEquals(1, dives.allDives().size)
        assertEquals(2, dives.recordsForDive(diveId).size)
        // Summary still comes from the primary (first) record.
        assertEquals(assertIs<ImportResult.CreatedDive>(first).recordId, dives.getDive(diveId)!!.primaryComputerRecordId)
    }

    @Test
    fun overlapNotConfirmedCreatesSeparateDive() {
        dives.import(incoming(deviceId = predator(), start = 1_000, duration = 3_600)) { false }
        val overlap = incoming(deviceId = petrel(), start = 1_120, duration = 3_500, fingerprint = "petrel-1")

        val result = dives.import(overlap) { false }
        assertIs<ImportResult.CreatedDive>(result)
        assertEquals(2, dives.allDives().size)
    }

    @Test
    fun nonOverlappingIsNewDive() {
        dives.import(incoming(deviceId = predator(), start = 1_000, duration = 3_600)) { false }
        val later = incoming(deviceId = petrel(), start = 100_000, duration = 3_600, fingerprint = "later")
        assertEquals(ImportDecision.NewDive, dives.classify(later))
    }

    @Test
    fun rawSamplesAndEventsArePreserved() {
        val raw = byteArrayOf(9, 8, 7, 6, 5)
        val result = dives.import(
            incoming(
                deviceId = predator(),
                rawData = raw,
                samples = listOf(
                    sampleAt(0, depthMm = 0),
                    sampleAt(60, depthMm = 12_000, tankPressures = mapOf(0 to 200_000)),
                    sampleAt(120, depthMm = 30_000, tankPressures = mapOf(0 to 180_000, 1 to 150_000)),
                ),
                events = listOf(
                    eventAt(60, EventType.GAS_SWITCH, value = 1),
                    eventAt(90, EventType.WARNING),
                ),
            ),
        ) { false }
        val recordId = assertIs<ImportResult.CreatedDive>(result).recordId

        assertContentEquals(raw, dives.rawData(recordId))

        val samples = dives.samplesForRecord(recordId)
        assertEquals(3, samples.size)
        assertEquals(30_000, samples[2].depthMm)
        assertEquals(mapOf(0 to 180_000, 1 to 150_000), samples[2].tankPressuresMbar)

        val events = dives.eventsForRecord(recordId)
        assertEquals(2, events.size)
        assertEquals(EventType.GAS_SWITCH, events[0].type)
        assertEquals(1L, events[0].value)
    }

    @Test
    fun unknownDeviceSkipsDuplicateCheck() {
        // No device id -> cannot dedupe by device; both imports create dives.
        dives.import(incoming(deviceId = null, fingerprint = "x")) { false }
        val decision = dives.classify(incoming(deviceId = null, start = 500_000, fingerprint = "x"))
        assertTrue(decision is ImportDecision.NewDive)
    }

    @Test
    fun anOverlappingDiveFromTheSameComputerIsADuplicate() {
        val petrel = devices.add(Device(vendor = "Shearwater", model = "Petrel"))
        val predator = devices.add(Device(vendor = "Shearwater", model = "Predator"))
        val first = dives.import(incoming(deviceId = petrel, start = 10_000, fingerprint = "macdive")) { false }
        // The same dive from another logbook: different fingerprint, same computer, same time.
        val again = dives.import(incoming(deviceId = petrel, start = 10_060, fingerprint = "cloud")) { false }
        assertTrue(again is ImportResult.SkippedDuplicate, "was $again")
        // Another computer on the same dive is still a merge candidate, not a duplicate.
        val other = dives.import(incoming(deviceId = predator, start = 10_000, fingerprint = "p")) { true }
        assertTrue(other is ImportResult.AttachedToDive, "was $other")
        assertTrue(first is ImportResult.CreatedDive)
    }
}
