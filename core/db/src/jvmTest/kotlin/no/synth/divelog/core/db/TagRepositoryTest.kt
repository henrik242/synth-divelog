package no.synth.divelog.core.db

import no.synth.divelog.core.model.Device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TagRepositoryTest {
    private val db = testDatabase()
    private val tags = TagRepository(db)
    private val dives = DiveRepository(db)
    private val devices = DeviceRepository(db)

    private fun aDive(start: Long, fp: String): Long {
        val dev = devices.add(Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = fp))
        return (dives.import(incoming(deviceId = dev, start = start, fingerprint = fp)) { false }
                as ImportResult.CreatedDive).diveId
    }

    @Test
    fun linkAndListTaggedDives() {
        val dive1 = aDive(1_000, "a")
        val dive2 = aDive(500_000, "b")
        val ccr = tags.add("CCR")

        tags.linkToDive(dive1, ccr)
        tags.linkToDive(dive2, ccr)

        assertEquals(listOf("CCR"), tags.tagsForDive(dive1).map { it.name })
        assertEquals(setOf(dive1, dive2), tags.divesForTag(ccr).map { it.id }.toSet())
    }

    @Test
    fun linkIsIdempotentAndUnlinkWorks() {
        val dive = aDive(1_000, "a")
        val tech = tags.add("Tech2")
        tags.linkToDive(dive, tech)
        tags.linkToDive(dive, tech) // duplicate link ignored
        assertEquals(1, tags.tagsForDive(dive).size)

        tags.unlinkFromDive(dive, tech)
        assertTrue(tags.tagsForDive(dive).isEmpty())
    }

    @Test
    fun renameChangesTheName() {
        val id = tags.add("Tec")
        tags.rename(id, "Tech2")
        assertEquals("Tech2", tags.get(id)?.name)
    }

    @Test
    fun getOrCreateReusesExisting() {
        val first = tags.getOrCreate("CCR")
        val second = tags.getOrCreate("CCR")
        assertEquals(first, second)
        assertEquals(1, tags.all().size)
    }

    @Test
    fun deletingTagRemovesLinks() {
        val dive = aDive(1_000, "a")
        val tech = tags.add("Tech2")
        tags.linkToDive(dive, tech)
        tags.delete(tech)
        assertTrue(tags.tagsForDive(dive).isEmpty())
        assertTrue(tags.all().isEmpty())
    }
}
