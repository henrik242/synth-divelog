package no.synth.divelog.core.db

import no.synth.divelog.core.model.Device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DeviceMergeTest {
    private val db = testDatabase()
    private val dives = DiveRepository(db)
    private val devices = DeviceRepository(db)

    @Test
    fun mergeMovesRecordsAndDeletesSource() {
        val a = devices.add(Device(vendor = "Shearwater", model = "Petrel", nickname = "keep"))
        val b = devices.add(Device(vendor = "Shearwater", model = "Petrel 3"))
        dives.import(incoming(deviceId = a, start = 1_000, fingerprint = "a1")) { false }
        dives.import(incoming(deviceId = b, start = 100_000, fingerprint = "b1")) { false }
        assertEquals(mapOf(a to 1L, b to 1L), devices.diveCounts())

        devices.merge(fromId = b, toId = a)

        assertNull(devices.get(b))
        assertEquals("keep", devices.get(a)?.nickname)
        assertEquals(mapOf(a to 2L), devices.diveCounts())
    }

    @Test
    fun newestDivesPicksEachDevicesLatestStart() {
        val a = devices.add(Device(vendor = "Suunto", model = "HelO2"))
        val b = devices.add(Device(vendor = "Shearwater", model = "Petrel"))
        dives.import(incoming(deviceId = a, start = 1_000, fingerprint = "a1")) { false }
        dives.import(incoming(deviceId = a, start = 50_000, fingerprint = "a2")) { false }
        dives.import(incoming(deviceId = b, start = 100_000, fingerprint = "b1")) { false }

        assertEquals(mapOf(a to (50_000L to 3_600), b to (100_000L to 3_600)), devices.newestDives())
    }
}
