package no.synth.divelog.core.db

import no.synth.divelog.core.model.Device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/** A record's start is stored in its dive's time frame, whichever frame its source used. */
class RecordFrameTest {
    private val db = testDatabase()
    private val dives = DiveRepository(db)
    private val devices = DeviceRepository(db)

    private val wallClock = 1_700_000_000L

    /** A dive downloaded with the wall clock only, and a copy from a zoned (+02:00) logbook attached. */
    private fun wallClockDiveWithZonedRecord(): Pair<Long, Long> {
        val petrel = devices.add(Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "1"))
        val helo2 = devices.add(Device(vendor = "Suunto", model = "HelO2", bluetoothAddress = "2"))
        val created = assertIs<ImportResult.CreatedDive>(
            dives.import(incoming(deviceId = petrel, start = wallClock, fingerprint = "a").copy(utcOffsetSeconds = 0)) { false },
        )
        // 30 s later on the wall clock, as a real UTC instant.
        val zoned = incoming(deviceId = helo2, start = wallClock + 30 - 7_200, fingerprint = "b").copy(utcOffsetSeconds = 7_200)
        val attached = assertIs<ImportResult.AttachedToDive>(dives.import(zoned) { true })
        return created.diveId to attached.recordId
    }

    @Test
    fun anAttachedRecordIsStoredInTheDivesFrame() {
        val (_, recordId) = wallClockDiveWithZonedRecord()
        assertEquals(wallClock + 30, assertNotNull(dives.record(recordId)).startEpochSeconds)
    }

    @Test
    fun aSplitRecordKeepsItsWallClock() {
        val (diveId, recordId) = wallClockDiveWithZonedRecord()
        val newDive = assertNotNull(dives.getDive(dives.splitRecordIntoNewDive(recordId)))
        val oldDive = assertNotNull(dives.getDive(diveId))
        assertEquals(wallClock + 30, newDive.startEpochSeconds + newDive.utcOffsetSeconds)
        assertEquals(oldDive.utcOffsetSeconds, newDive.utcOffsetSeconds)
    }

    @Test
    fun reparseStoresTheStartInTheDivesFrame() {
        val (_, recordId) = wallClockDiveWithZonedRecord()
        dives.reparseRecord(recordId, incoming(start = wallClock + 60 - 3_600, fingerprint = "b").copy(utcOffsetSeconds = 3_600))
        assertEquals(wallClock + 60, assertNotNull(dives.record(recordId)).startEpochSeconds)
    }
}
