package no.synth.divelog.core.db

import no.synth.divelog.core.model.Device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class MergeSplitTest {
    private val db = testDatabase()
    private val dives = DiveRepository(db)
    private val devices = DeviceRepository(db)
    private val buddies = BuddyRepository(db)

    private fun twoComputerDive(): Long {
        val predator = devices.add(Device(vendor = "Shearwater", model = "Predator", bluetoothAddress = "1"))
        val petrel = devices.add(Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "2"))
        val first = dives.import(incoming(deviceId = predator, start = 1_000, duration = 3_600, fingerprint = "p1")) { false }
        val diveId = assertIs<ImportResult.CreatedDive>(first).diveId
        dives.import(incoming(deviceId = petrel, start = 1_050, duration = 3_550, fingerprint = "p2")) { true }
        return diveId
    }

    @Test
    fun splitMovesSecondComputerToItsOwnDive() {
        val diveId = twoComputerDive()
        val records = dives.recordsForDive(diveId)
        assertEquals(2, records.size)
        val secondary = records.first { it.id != dives.getDive(diveId)!!.primaryComputerRecordId }

        val newDiveId = dives.splitRecordIntoNewDive(secondary.id)

        assertEquals(1, dives.recordsForDive(diveId).size)
        assertEquals(1, dives.recordsForDive(newDiveId).size)
        assertEquals(secondary.id, dives.getDive(newDiveId)!!.primaryComputerRecordId)
    }

    @Test
    fun splittingPrimaryReassignsOldDivePrimary() {
        val diveId = twoComputerDive()
        val primaryRecord = dives.getDive(diveId)!!.primaryComputerRecordId!!

        val newDiveId = dives.splitRecordIntoNewDive(primaryRecord)

        val oldPrimary = dives.getDive(diveId)!!.primaryComputerRecordId
        assertEquals(primaryRecord, dives.getDive(newDiveId)!!.primaryComputerRecordId)
        // Old dive keeps a record, and its primary is no longer the split one.
        assertEquals(1, dives.recordsForDive(diveId).size)
        assertEquals(dives.recordsForDive(diveId).single().id, oldPrimary)
    }

    @Test
    fun cannotSplitTheOnlyRecord() {
        val dev = devices.add(Device(vendor = "Shearwater", model = "Predator", bluetoothAddress = "1"))
        val created = assertIs<ImportResult.CreatedDive>(dives.import(incoming(deviceId = dev)) { false })
        assertFailsWith<IllegalArgumentException> {
            dives.splitRecordIntoNewDive(created.recordId)
        }
    }

    @Test
    fun mergeDivesFoldsRecordsAndBuddiesThenDropsSource() {
        val predator = devices.add(Device(vendor = "Shearwater", model = "Predator", bluetoothAddress = "1"))
        val petrel = devices.add(Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "2"))
        val a = assertIs<ImportResult.CreatedDive>(dives.import(incoming(deviceId = predator, start = 1_000, fingerprint = "a")) { false }).diveId
        val b = assertIs<ImportResult.CreatedDive>(dives.import(incoming(deviceId = petrel, start = 500_000, fingerprint = "b")) { false }).diveId

        val buddy = buddies.add("Alex")
        buddies.linkToDive(b, buddy)

        dives.mergeDives(sourceDiveId = b, targetDiveId = a)

        assertNull(dives.getDive(b))
        assertEquals(2, dives.recordsForDive(a).size)
        assertEquals(listOf("Alex"), buddies.buddiesForDive(a).map { it.name })
        assertEquals(1, dives.allDives().size)
    }

    @Test
    fun cannotMergeDiveIntoItself() {
        val dev = devices.add(Device(vendor = "Shearwater", model = "Predator", bluetoothAddress = "1"))
        val a = assertIs<ImportResult.CreatedDive>(dives.import(incoming(deviceId = dev)) { false }).diveId
        assertFailsWith<IllegalArgumentException> { dives.mergeDives(a, a) }
    }
}
