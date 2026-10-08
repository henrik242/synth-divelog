package no.synth.divelog.core.db

import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.Tank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull

class MergeSplitTest {
    private val db = testDatabase()
    private val dives = DiveRepository(db)
    private val devices = DeviceRepository(db)
    private val buddies = BuddyRepository(db)
    private val tags = TagRepository(db)
    private val gases = GasRepository(db)
    private val sites = SiteRepository(db)

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
        val secondary = records.first { it.id != assertNotNull(dives.getDive(diveId)).primaryComputerRecordId }

        val newDiveId = dives.splitRecordIntoNewDive(secondary.id)

        assertEquals(1, dives.recordsForDive(diveId).size)
        assertEquals(1, dives.recordsForDive(newDiveId).size)
        assertEquals(secondary.id, assertNotNull(dives.getDive(newDiveId)).primaryComputerRecordId)
    }

    @Test
    fun splittingPrimaryReassignsOldDivePrimary() {
        val diveId = twoComputerDive()
        val primaryRecord = assertNotNull(assertNotNull(dives.getDive(diveId)).primaryComputerRecordId)

        val newDiveId = dives.splitRecordIntoNewDive(primaryRecord)

        val oldPrimary = assertNotNull(dives.getDive(diveId)).primaryComputerRecordId
        assertEquals(primaryRecord, assertNotNull(dives.getDive(newDiveId)).primaryComputerRecordId)
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

    @Test
    fun mergeDivesKeepsTheSourcesTagsTanksAndFields() {
        val predator = devices.add(Device(vendor = "Shearwater", model = "Predator", bluetoothAddress = "1"))
        val petrel = devices.add(Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "2"))
        val a = assertIs<ImportResult.CreatedDive>(dives.import(incoming(deviceId = predator, start = 1_000, fingerprint = "a")) { false }).diveId
        val b = assertIs<ImportResult.CreatedDive>(dives.import(incoming(deviceId = petrel, start = 500_000, fingerprint = "b")) { false }).diveId

        val ean32 = gases.getOrCreateGasMix(320, 0)
        val oxygen = gases.getOrCreateGasMix(1_000, 0)
        // Target: one tank of EAN32 without a size, a tag, a rating.
        gases.addTank(Tank(diveId = a, index = 0, gasMixId = ean32))
        tags.linkToDive(a, tags.getOrCreate("wreck"))
        dives.updateDive(assertNotNull(dives.getDive(a)).copy(rating = 4))
        // Source: the same EAN32 tank with its size, an oxygen tank, tags, notes and a site.
        gases.addTank(Tank(diveId = b, index = 0, volumeMl = 12_000, startPressureMbar = 200_000, gasMixId = ean32))
        gases.addTank(Tank(diveId = b, index = 1, volumeMl = 3_000, gasMixId = oxygen))
        tags.linkToDive(b, tags.getOrCreate("wreck"))
        tags.linkToDive(b, tags.getOrCreate("deep"))
        val site = sites.getOrCreateSite("Norway", "Gulen", "Blue Hole")
        dives.updateDive(assertNotNull(dives.getDive(b)).copy(notes = "Great viz", rating = 2, visibility = 15_000, siteId = site))

        dives.mergeDives(sourceDiveId = b, targetDiveId = a)

        assertEquals(listOf("deep", "wreck"), tags.tagsForDive(a).map { it.name })
        val tanks = gases.tanksForDive(a)
        assertEquals(2, tanks.size)
        assertEquals(12_000, tanks.single { it.gasMixId == ean32 }.volumeMl)
        assertEquals(200_000, tanks.single { it.gasMixId == ean32 }.startPressureMbar)
        assertEquals(3_000, tanks.single { it.gasMixId == oxygen }.volumeMl)
        val merged = assertNotNull(dives.getDive(a))
        assertEquals("Great viz", merged.notes)
        assertEquals(4, merged.rating, "the target's own value is kept")
        assertEquals(15_000, merged.visibility)
        assertEquals(site, merged.siteId)
    }

    @Test
    fun mergeMovesRecordsIntoTheTargetsTimeFrame() {
        val predator = devices.add(Device(vendor = "Shearwater", model = "Predator", bluetoothAddress = "1"))
        val petrel = devices.add(Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "2"))
        // Target: wall clock, no zone. Source: the same 10:00 wall clock, zoned at +02:00.
        val a = assertIs<ImportResult.CreatedDive>(
            dives.import(incoming(deviceId = predator, start = 36_000, fingerprint = "a").copy(utcOffsetSeconds = 0)) { false },
        ).diveId
        val b = assertIs<ImportResult.CreatedDive>(
            dives.import(incoming(deviceId = petrel, start = 900_000, fingerprint = "b").copy(utcOffsetSeconds = 7_200)) { false },
        )
        dives.updateDive(assertNotNull(dives.getDive(b.diveId)).copy(startEpochSeconds = 36_000 - 7_200))

        dives.mergeDives(sourceDiveId = b.diveId, targetDiveId = a)

        // The source record's 900_000 + 7_200 wall clock, in the target's zero-offset frame.
        assertEquals(907_200, assertNotNull(dives.record(b.recordId)).startEpochSeconds)
    }
}
