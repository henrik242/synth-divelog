package no.synth.divelog.core.db

import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.Tank
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

    @Test
    fun mergingOneComputersDivesJoinsThemIntoOneRecord() {
        val petrel = devices.add(Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "2"))
        val profile = listOf(sampleAt(0, 0), sampleAt(300, 20_000), sampleAt(600, 1_000))
        val a = assertIs<ImportResult.CreatedDive>(
            dives.import(incoming(deviceId = petrel, start = 10_000, duration = 600, maxDepthMm = 20_000, fingerprint = "a", samples = profile)) { false },
        )
        val b = assertIs<ImportResult.CreatedDive>(
            dives.import(
                incoming(
                    deviceId = petrel, start = 12_000, duration = 600, maxDepthMm = 25_000, fingerprint = "b",
                    samples = listOf(sampleAt(0, 1_000), sampleAt(300, 25_000), sampleAt(600, 0)),
                    events = listOf(eventAt(100, EventType.BOOKMARK)),
                ),
            ) { false },
        )
        assertTrue(dives.canMerge(listOf(a.diveId, b.diveId)))

        val merged = dives.mergeDives(listOf(b.diveId, a.diveId))

        assertEquals(a.diveId, merged, "the earliest dive is kept")
        assertNull(dives.getDive(b.diveId))
        val record = dives.recordsForDive(merged).single()
        assertEquals(a.recordId, record.id)
        assertEquals(2_600, record.durationSeconds)
        assertEquals(25_000, record.maxDepthMm)
        assertEquals(DiveRepository.CONCATENATED_FORMAT_ID, record.rawFormatId)
        assertEquals("b", record.fingerprint)
        assertEquals(
            listOf(0 to 0, 300 to 20_000, 600 to 1_000, 601 to 0, 1_999 to 0, 2_000 to 1_000, 2_300 to 25_000, 2_600 to 0),
            dives.samplesForRecord(record.id).map { it.timeOffsetSeconds to it.depthMm },
        )
        assertEquals(listOf(2_100), dives.eventsForRecord(record.id).map { it.timeOffsetSeconds })
        val surface = dives.samplesForRecord(record.id).single { it.timeOffsetSeconds == 601 }
        assertEquals(listOf(0, 0, 0), listOf(surface.stopDepthMm, surface.stopTimeSeconds, surface.ttsSeconds))
        val dive = assertNotNull(dives.getDive(merged))
        assertEquals(2_600, dive.durationSeconds)
        assertEquals(25_000, dive.maxDepthMm)
        assertEquals(record.id, dive.primaryComputerRecordId)
        // The second dive downloaded again is already here.
        assertIs<ImportDecision.Duplicate>(dives.classify(incoming(deviceId = petrel, start = 12_000, duration = 600, fingerprint = "b")))
    }

    @Test
    fun mergingOverlappingDivesFromTwoComputersKeepsAProfileEach() {
        val predator = devices.add(Device(vendor = "Shearwater", model = "Predator", bluetoothAddress = "1"))
        val petrel = devices.add(Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "2"))
        val a = assertIs<ImportResult.CreatedDive>(dives.import(incoming(deviceId = predator, start = 10_000, fingerprint = "a")) { false })
        val b = assertIs<ImportResult.CreatedDive>(dives.import(incoming(deviceId = petrel, start = 10_060, fingerprint = "b")) { false })

        val merged = dives.mergeDives(listOf(a.diveId, b.diveId))

        assertEquals(setOf(a.recordId, b.recordId), dives.recordsForDive(merged).map { it.id }.toSet())
        assertEquals("test-format", assertNotNull(dives.record(b.recordId)).rawFormatId)
        assertEquals(a.recordId, assertNotNull(dives.getDive(merged)).primaryComputerRecordId)
    }

    @Test
    fun oneComputerLoggingTwoDivesAtOnceCannotMerge() {
        val petrel = devices.add(Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "2"))
        val a = assertIs<ImportResult.CreatedDive>(dives.import(incoming(deviceId = petrel, start = 10_000, fingerprint = "a")) { false }).diveId
        val b = dives.importAsNewDive(incoming(deviceId = petrel, start = 10_600, fingerprint = "b")).diveId

        assertFalse(dives.canMerge(listOf(a, b)))
        assertFalse(dives.canMerge(listOf(a)))
        assertFailsWith<IllegalArgumentException> { dives.mergeDives(listOf(a, b)) }
        assertEquals(2, dives.allDives().size)
    }

    @Test
    fun joinedDivesKeepTankPressuresOnTheirTanks() {
        val petrel = devices.add(Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "2"))
        val a = assertIs<ImportResult.CreatedDive>(
            dives.import(incoming(deviceId = petrel, start = 10_000, duration = 600, fingerprint = "a", samples = listOf(sampleAt(0, 0, mapOf(0 to 200_000))))) { false },
        )
        val b = assertIs<ImportResult.CreatedDive>(
            dives.import(incoming(deviceId = petrel, start = 12_000, duration = 600, fingerprint = "b", samples = listOf(sampleAt(0, 0, mapOf(0 to 180_000))))) { false },
        )
        val air = gases.getOrCreateGasMix(210, 0)
        val ean32 = gases.getOrCreateGasMix(320, 0)
        gases.addTank(Tank(diveId = a.diveId, index = 0, volumeMl = 12_000, gasMixId = air))
        gases.addTank(Tank(diveId = b.diveId, index = 0, volumeMl = 12_000, gasMixId = ean32))

        val merged = dives.mergeDives(listOf(a.diveId, b.diveId))

        val ean32Index = gases.tanksForDive(merged).single { it.gasMixId == ean32 }.index
        assertEquals(1, ean32Index)
        val pressures = dives.samplesForRecord(a.recordId).associate { it.timeOffsetSeconds to it.tankPressuresMbar }
        assertEquals(mapOf(0 to 200_000), pressures[0])
        assertEquals(mapOf(ean32Index to 180_000), pressures[2_000])
    }

    @Test
    fun mergingDivesWithoutComputersSpansThemAll() {
        val first = dives.insertDive(Dive(startEpochSeconds = 10_000, utcOffsetSeconds = 0, durationSeconds = 600, maxDepthMm = 10_000, meanDepthMm = 5_000))
        val second = dives.insertDive(Dive(startEpochSeconds = 12_000, utcOffsetSeconds = 0, durationSeconds = 1_200, maxDepthMm = 20_000, meanDepthMm = 8_000))

        val merged = assertNotNull(dives.getDive(dives.mergeDives(listOf(second, first))))

        assertEquals(first, merged.id)
        assertEquals(3_200, merged.durationSeconds)
        assertEquals(20_000, merged.maxDepthMm)
        assertEquals(7_000, merged.meanDepthMm)
    }

    @Test
    fun aJoinedRecordGivesTheNewestFingerprintOfItsDives() {
        val petrel = devices.add(Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "2"))
        val a = assertIs<ImportResult.CreatedDive>(dives.import(incoming(deviceId = petrel, start = 10_000, duration = 600, fingerprint = "a")) { false }).diveId
        dives.import(incoming(deviceId = petrel, start = 12_000, duration = 600, fingerprint = "b")) { false }
        val c = assertIs<ImportResult.CreatedDive>(dives.import(incoming(deviceId = petrel, start = 14_000, duration = 600, fingerprint = "c")) { false }).diveId

        dives.mergeDives(listOf(a, c))

        assertEquals("c", dives.newestFingerprint(petrel))
    }
}
