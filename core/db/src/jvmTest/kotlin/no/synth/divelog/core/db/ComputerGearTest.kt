package no.synth.divelog.core.db

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearKind
import no.synth.divelog.core.model.GearService
import no.synth.divelog.core.model.ImportedGear

class ComputerGearTest {
    private val db = testDatabase()
    private val devices = DeviceRepository(db)
    private val gear = GearRepository(db)

    @Test
    fun aNewComputerGetsAGearItem() {
        val id = devices.add(Device(vendor = "Shearwater", model = "Perdix", serial = "ABC"))
        val item = gear.forDevice(id)
        assertEquals("Shearwater Perdix", item?.name)
        assertEquals(GearKind.COMPUTER, item?.kind)
        assertEquals("ABC", item?.serialNumber)
        assertEquals("Shearwater Perdix", devices.get(id)?.nickname)

        val imported = devices.getOrCreate(Device(vendor = "Suunto", model = "Vyper"))
        assertEquals("Suunto Vyper", gear.forDevice(imported)?.name)
    }

    @Test
    fun renamingTheGearRenamesTheComputer() {
        val id = devices.add(Device(vendor = "Shearwater", model = "Perdix"))
        val item = gear.forDevice(id) ?: error("no gear")
        gear.update(item.copy(name = "Blue"))
        assertEquals("Blue", devices.get(id)?.nickname)
    }

    @Test
    fun mergingComputersMergesTheirGear() {
        val a = devices.add(Device(vendor = "Shearwater", model = "Perdix", serial = "1"))
        val b = devices.add(Device(vendor = "Shearwater", model = "Perdix", serial = "2"))
        val aGear = gear.forDevice(a) ?: error("no gear")
        gear.update(aGear.copy(shop = "Dive shop"))
        gear.addService(GearService(gearId = aGear.id, epochDay = 20_000))

        devices.merge(a, b)

        val merged = gear.forDevice(b) ?: error("no gear")
        assertEquals("Dive shop", merged.shop)
        assertEquals(listOf(20_000), gear.services(merged.id).map { it.epochDay })
        assertEquals(1, gear.all().size)
    }

    @Test
    fun mergingIntoAComputerWithoutGearMovesTheItem() {
        val a = devices.add(Device(vendor = "Shearwater", model = "Perdix", serial = "1"))
        val b = devices.add(Device(vendor = "Shearwater", model = "Perdix", serial = "2"))
        val aGear = gear.forDevice(a)?.id ?: error("no gear")
        gear.forDevice(b)?.let { gear.delete(it.id) }

        devices.merge(a, b)

        assertEquals(aGear, gear.forDevice(b)?.id)
    }

    @Test
    fun downloadsDoNotChangeTheName() {
        val id = devices.add(Device(vendor = "Shearwater", model = "Perdix"))
        gear.forDevice(id)?.let { gear.update(it.copy(name = "Blue")) }
        devices.update(devices.get(id)?.copy(firmware = "90", nickname = "Something else") ?: error("no device"))
        assertEquals("Blue", devices.get(id)?.nickname)
    }

    @Test
    fun forgettingKeepsTheGearItemUnlinked() {
        val id = devices.add(Device(vendor = "Shearwater", model = "Perdix"))
        val item = gear.forDevice(id) ?: error("no gear")
        gear.addService(GearService(gearId = item.id, epochDay = 20_000))
        devices.forget(id)
        assertNull(devices.get(id))
        assertNull(gear.get(item.id)?.deviceId)
        assertEquals(listOf(20_000), gear.services(item.id).map { it.epochDay })
    }

    @Test
    fun foldingMovesDiveLinks() {
        val dives = DiveRepository(db)
        val a = devices.add(Device(vendor = "Shearwater", model = "Perdix", serial = "1"))
        val b = devices.add(Device(vendor = "Shearwater", model = "Perdix", serial = "2"))
        val aGear = gear.forDevice(a)?.id ?: error("no gear")
        val bGear = gear.forDevice(b)?.id ?: error("no gear")
        val other = DeviceRepository(db).add(Device(vendor = "Suunto", model = "Vyper"))
        val onA = dives.importAsNewDive(incoming(deviceId = other, start = 1_000, fingerprint = "x")).diveId
        val onBoth = dives.importAsNewDive(incoming(deviceId = other, start = 500_000, fingerprint = "y")).diveId
        gear.linkToDive(onA, aGear)
        gear.linkToDive(onBoth, aGear)
        gear.linkToDive(onBoth, bGear)

        devices.merge(a, b)

        assertEquals(setOf(onA, onBoth), gear.diveIdsForGear(bGear).toSet())
    }

    @Test
    fun aComputerAlreadyInTheGearListIsLinkedOnFirstDownload() {
        val mine = gear.add(GearItem(name = "My Perdix", kind = GearKind.COMPUTER, serialNumber = "ABC"))
        val id = devices.add(Device(vendor = "Shearwater", model = "Perdix", serial = "ABC"))
        assertEquals(mine, gear.forDevice(id)?.id)
        assertEquals("My Perdix", devices.get(id)?.nickname)
        assertEquals(1, gear.all().size)
    }

    @Test
    fun importingAComputerLinksItToTheDownloadedOne() {
        val id = devices.add(Device(vendor = "Shearwater", model = "Perdix", serial = "ABC"))
        gear.import(listOf(ImportedGear(GearItem(name = "Perdix", kind = GearKind.COMPUTER, brand = "Shearwater", serialNumber = "ABC"))))
        assertEquals("Perdix", gear.forDevice(id)?.name)
        assertEquals(1, gear.all().size)
    }

    @Test
    fun tidyingALegacyImportRenamesItsGear() {
        val id = devices.add(Device(vendor = "Imported", model = "Shearwater Perdix"))
        devices.tidyLegacyImports()
        assertEquals("Shearwater Perdix", gear.forDevice(id)?.name)
        assertEquals("Shearwater", gear.forDevice(id)?.brand)
        assertEquals("Shearwater Perdix", devices.get(id)?.nickname)
    }

    @Test
    fun linkingByHandFoldsInTheMadeItem() {
        val id = devices.add(Device(vendor = "Shearwater", model = "Perdix", serial = "ABC"))
        gear.addService(GearService(gearId = gear.forDevice(id)?.id ?: error("no gear"), epochDay = 20_000))
        val mine = gear.add(GearItem(name = "My Perdix", kind = GearKind.COMPUTER, purchasedEpochDay = 19_000))

        gear.linkToDevice(mine, id)

        val linked = gear.forDevice(id)
        assertEquals(mine, linked?.id)
        assertEquals(19_000, linked?.purchasedEpochDay)
        assertEquals("ABC", linked?.serialNumber)
        assertEquals(listOf(20_000), gear.services(mine).map { it.epochDay })
        assertEquals(1, gear.all().size)
        assertEquals("My Perdix", devices.get(id)?.nickname)
    }

    @Test
    fun aSerialOnlyLinksWithinTheMaker() {
        val mares = gear.add(GearItem(name = "Puck", kind = GearKind.COMPUTER, brand = "Mares", serialNumber = "123"))
        val suunto = devices.add(Device(vendor = "Suunto", model = "Zoop", serial = "123"))
        assertEquals(null, gear.get(mares)?.deviceId)
        assertEquals("Suunto Zoop", gear.forDevice(suunto)?.name)

        // MacDive's longer maker name still matches.
        val perdix = gear.add(GearItem(name = "Perdix", kind = GearKind.COMPUTER, brand = "Shearwater Research", serialNumber = "ABC"))
        val shearwater = devices.add(Device(vendor = "Shearwater", model = "Perdix", serial = "ABC"))
        assertEquals(perdix, gear.forDevice(shearwater)?.id)
    }

    @Test
    fun twoCandidatesLinkNeither() {
        val first = gear.add(GearItem(name = "One", kind = GearKind.COMPUTER, serialNumber = "7"))
        val second = gear.add(GearItem(name = "Two", kind = GearKind.COMPUTER, serialNumber = "7"))
        val id = devices.add(Device(vendor = "Suunto", model = "Vyper", serial = "7"))
        assertEquals(setOf(first, second), gear.all().filter { it.deviceId == null }.map { it.id }.toSet())
        assertEquals("Suunto Vyper", gear.forDevice(id)?.name)
    }

    @Test
    fun importingDoesNotLinkAnotherMakersComputer() {
        val mares = devices.add(Device(vendor = "Mares", model = "Puck", serial = "123"))
        gear.import(listOf(ImportedGear(GearItem(name = "Zoop", kind = GearKind.COMPUTER, brand = "Suunto", serialNumber = "123"))))
        assertEquals("Mares Puck", gear.forDevice(mares)?.name)
        assertEquals(2, gear.all().size)

        // Nor one of two computers with the serial.
        devices.add(Device(vendor = "Mares", model = "Quad", serial = "123"))
        gear.import(listOf(ImportedGear(GearItem(name = "Mine", kind = GearKind.COMPUTER, brand = "Mares", serialNumber = "123"))))
        assertEquals(null, gear.all().single { it.name == "Mine" }.deviceId)
    }
}
