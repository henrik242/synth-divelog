package no.synth.divelog.core.model

import kotlin.test.Test
import kotlin.test.assertEquals

class ComputerNamesTest {
    @Test
    fun splitsKnownVendors() {
        assertEquals("Shearwater" to "Petrel", ComputerNames.split("Shearwater Research, Inc Petrel"))
        assertEquals("Shearwater" to "Predator", ComputerNames.split("Shearwater Predator"))
        assertEquals("Suunto" to "HelO2", ComputerNames.split("Suunto HelO2"))
        assertEquals("Heinrichs Weikamp" to "OSTC 4", ComputerNames.split("Heinrichs Weikamp OSTC 4"))
    }

    @Test
    fun keepsUnknownNamesWhole() {
        assertEquals("" to "No Computer", ComputerNames.split("No Computer"))
        assertEquals("" to ComputerNames.UNKNOWN, ComputerNames.split("  "))
        assertEquals("" to ComputerNames.UNKNOWN, ComputerNames.split(null))
        // A bare vendor name is a model, not a vendor with nothing after it.
        assertEquals("" to "Suunto", ComputerNames.split("Suunto"))
    }

    @Test
    fun keyIgnoresCaseSpacingAndHowTheNameWasSplit() {
        assertEquals(ComputerNames.key("Suunto", "Zoop"), ComputerNames.key("suunto", " ZOOP "))
        assertEquals(ComputerNames.key("Shearwater", "Petrel"), ComputerNames.key("", "Shearwater Petrel"))
    }

    @Test
    fun fullNameSkipsAnEmptyVendor() {
        assertEquals("Shearwater Petrel", ComputerNames.fullName(Device(vendor = "Shearwater", model = "Petrel")))
        assertEquals("No Computer", ComputerNames.fullName(Device(vendor = "", model = "No Computer")))
    }
}
