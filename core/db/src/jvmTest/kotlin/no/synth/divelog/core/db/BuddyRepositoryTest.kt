// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.db

import no.synth.divelog.core.model.Device
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BuddyRepositoryTest {
    private val db = testDatabase()
    private val buddies = BuddyRepository(db)
    private val dives = DiveRepository(db)
    private val devices = DeviceRepository(db)

    private fun aDive(start: Long, fp: String): Long {
        val dev = devices.add(Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = fp))
        return (dives.import(incoming(deviceId = dev, start = start, fingerprint = fp)) { false }
                as ImportResult.CreatedDive).diveId
    }

    @Test
    fun linkAndListSharedDives() {
        val dive1 = aDive(1_000, "a")
        val dive2 = aDive(500_000, "b")
        val alex = buddies.add("Alex")

        buddies.linkToDive(dive1, alex)
        buddies.linkToDive(dive2, alex)

        assertEquals(listOf("Alex"), buddies.buddiesForDive(dive1).map { it.name })
        assertEquals(setOf(dive1, dive2), buddies.divesForBuddy(alex).map { it.id }.toSet())
    }

    @Test
    fun linkIsIdempotentAndUnlinkWorks() {
        val dive = aDive(1_000, "a")
        val sam = buddies.add("Sam")
        buddies.linkToDive(dive, sam)
        buddies.linkToDive(dive, sam) // duplicate link ignored
        assertEquals(1, buddies.buddiesForDive(dive).size)

        buddies.unlinkFromDive(dive, sam)
        assertTrue(buddies.buddiesForDive(dive).isEmpty())
    }

    @Test
    fun deletingBuddyRemovesLinks() {
        val dive = aDive(1_000, "a")
        val sam = buddies.add("Sam")
        buddies.linkToDive(dive, sam)
        buddies.delete(sam)
        assertTrue(buddies.buddiesForDive(dive).isEmpty())
        assertTrue(buddies.all().isEmpty())
    }
}
