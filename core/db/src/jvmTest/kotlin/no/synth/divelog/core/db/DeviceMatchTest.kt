package no.synth.divelog.core.db

import no.synth.divelog.core.model.Device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class DeviceMatchTest {
    private val db = testDatabase()
    private val dives = DiveRepository(db)
    private val devices = DeviceRepository(db)

    @Test
    fun importFindsADownloadedComputerBySerial() {
        val downloaded = devices.getOrCreate(
            Device(vendor = "Suunto", model = "HelO2", serial = "94803", bluetoothAddress = "usb-serial:suunto-vyper2:94803"),
        )
        // A logbook spells the model differently, but the serial is the same unit.
        assertEquals(downloaded, devices.getOrCreate(Device(vendor = "Suunto", model = "Helo 2", serial = "94803")))
    }

    @Test
    fun importFindsADownloadedComputerByModelAndTeachesItTheSerial() {
        val downloaded = devices.getOrCreate(
            Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "serial-spp:shearwater-petrel"),
        )
        assertEquals(downloaded, devices.getOrCreate(Device(vendor = "Shearwater", model = "Petrel", serial = "A1B2C3D4")))
        assertEquals("A1B2C3D4", devices.get(downloaded)?.serial)
        assertEquals("serial-spp:shearwater-petrel", devices.get(downloaded)?.bluetoothAddress)
    }

    @Test
    fun downloadAdoptsAnImportedComputer() {
        val imported = devices.getOrCreate(Device(vendor = "Shearwater", model = "Petrel", serial = "A1B2C3D4"))
        val download = Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "serial-spp:shearwater-petrel")
        assertEquals(imported, devices.findId(download))
        assertEquals(imported, devices.getOrCreate(download))
        // From now on downloads find it by their own identity.
        assertEquals("serial-spp:shearwater-petrel", devices.get(imported)?.bluetoothAddress)
    }

    @Test
    fun aDifferentSerialIsADifferentComputer() {
        val first = devices.getOrCreate(Device(vendor = "Suunto", model = "HelO2", serial = "111"))
        val second = devices.getOrCreate(Device(vendor = "Suunto", model = "HelO2", serial = "222"))
        assertNotEquals(first, second)
        // With two candidates and no serial to tell them apart, do not guess.
        assertNull(devices.findMatch(Device(vendor = "Suunto", model = "HelO2")))
    }

    @Test
    fun sameSerialFromAnotherVendorIsNotAMatch() {
        val suunto = devices.getOrCreate(Device(vendor = "Suunto", model = "HelO2", serial = "123"))
        assertNotEquals(suunto, devices.getOrCreate(Device(vendor = "Mares", model = "Puck", serial = "123")))
    }

    @Test
    fun tidiesLegacyImportsAndMergesTheSameComputer() {
        val download = devices.add(Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "serial-spp:shearwater-petrel"))
        val long = devices.add(Device(vendor = "Imported", model = "Shearwater Research, Inc Petrel", bluetoothAddress = "import:Shearwater Research, Inc Petrel"))
        val short = devices.add(Device(vendor = "Imported", model = "Shearwater Petrel", bluetoothAddress = "import:Shearwater Petrel"))
        val noName = devices.add(Device(vendor = "Imported", model = "MacDive XML", bluetoothAddress = "import:MacDive XML"))
        dives.import(incoming(deviceId = long, start = 1_000, fingerprint = "a")) { false }
        dives.import(incoming(deviceId = short, start = 100_000, fingerprint = "b")) { false }

        devices.tidyLegacyImports()
        devices.tidyLegacyImports() // idempotent

        val all = devices.all()
        assertEquals(listOf("Shearwater Petrel" to "serial-spp:shearwater-petrel", "Unknown computer" to null),
            all.map { listOf(it.vendor, it.model).filter { s -> s.isNotEmpty() }.joinToString(" ") to it.bluetoothAddress }
                .sortedBy { it.first })
        assertEquals(mapOf(download to 2L), devices.diveCounts())
        assertEquals(noName, all.single { it.model == "Unknown computer" }.id)
    }
}
