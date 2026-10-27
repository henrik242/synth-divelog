package no.synth.divelog.core.db

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.GearItem
import no.synth.divelog.core.model.GearKind
import no.synth.divelog.core.model.GearService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GearRepositoryTest {
    private val db = testDatabase()
    private val gear = GearRepository(db)

    @Test
    fun addUpdateAndRead() {
        val id = gear.add(GearItem(name = "Reg", kind = GearKind.FIRST_STAGE, purchasedEpochDay = 19_000))
        gear.update(GearItem(id = id, name = "Apeks XTX50", kind = GearKind.FIRST_STAGE, shop = "Dive shop", retired = true))

        val item = gear.get(id)
        assertEquals("Apeks XTX50", item?.name)
        assertEquals(GearKind.FIRST_STAGE, item?.kind)
        assertNull(item?.purchasedEpochDay)
        assertEquals("Dive shop", item?.shop)
        assertEquals(true, item?.retired)
        assertEquals(false, gear.get(gear.add(GearItem(name = "New")))?.retired)
    }

    @Test
    fun servicesNewestFirstAndLatestPerItem() = runBlocking {
        val reg = gear.add(GearItem(name = "Reg"))
        val bcd = gear.add(GearItem(name = "bcd"))
        gear.addService(GearService(gearId = reg, epochDay = 19_000))
        gear.addService(GearService(gearId = reg, epochDay = 19_400, notes = "First stage"))

        assertEquals(listOf(19_400, 19_000), gear.services(reg).map { it.epochDay })
        // Sorted by name, ignoring case.
        val expected = listOf("bcd" to null, "Reg" to 19_400)
        assertEquals(expected, gear.allWithLastService().map { (g, last) -> g.name to last })
        assertEquals(expected, gear.allWithLastServiceFlow().first().map { (g, last) -> g.name to last })
    }

    @Test
    fun deletingGearDeletesItsServices() {
        val id = gear.add(GearItem(name = "Light"))
        gear.addService(GearService(gearId = id, epochDay = 19_000))
        gear.delete(id)
        assertTrue(gear.all().isEmpty())
        assertTrue(gear.services(id).isEmpty())
    }

    @Test
    fun gearOnDives() {
        val dives = DiveRepository(db)
        val device = DeviceRepository(db).add(Device(vendor = "Shearwater", model = "Petrel"))
        val dive = dives.importAsNewDive(incoming(deviceId = device, fingerprint = "a")).diveId
        val wing = gear.add(GearItem(name = "Wing"))
        val fins = gear.add(GearItem(name = "fins"))
        gear.linkToDive(dive, wing)
        gear.linkToDive(dive, fins)
        gear.linkToDive(dive, wing)
        assertEquals(listOf("fins", "Wing"), gear.gearForDive(dive).map { it.name })
        assertEquals(listOf(dive), gear.diveIdsForGear(wing))
        // The Petrel logged the dive, so its gear item is on it too.
        val petrel = gear.forDevice(device)?.id
        assertEquals(mapOf(dive to setOf(wing, fins, petrel)), gear.gearIdsByDive())
        assertEquals(listOf(dive), petrel?.let(gear::diveIdsForGear))

        gear.unlinkFromDive(dive, fins)
        assertEquals(listOf("Wing"), gear.gearForDive(dive).map { it.name })

        gear.delete(wing)
        assertTrue(gear.gearForDive(dive).isEmpty())
    }
}
