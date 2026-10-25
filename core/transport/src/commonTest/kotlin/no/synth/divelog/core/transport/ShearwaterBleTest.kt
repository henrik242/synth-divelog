package no.synth.divelog.core.transport

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ShearwaterBleTest {
    @Test
    fun aComputerIsKnownByNameOrByTheServiceWithoutOne() {
        assertTrue(ShearwaterBle.isKnown("Peregrine TX", advertisesService = false))
        assertTrue(ShearwaterBle.isKnown("Petrel 2", advertisesService = false))
        assertTrue(ShearwaterBle.isKnown(null, advertisesService = true))
        assertTrue(ShearwaterBle.isKnown("", advertisesService = true)) // CoreBluetooth may report an empty name
        assertFalse(ShearwaterBle.isKnown("Perdix 2", advertisesService = true))
        assertFalse(ShearwaterBle.isKnown(null, advertisesService = false))
    }

    @Test
    fun theSerialLinkIsSearchedInShearwatersServiceFirstAndNeverInSigOnes() {
        val services = listOf(
            "180A", // device information, as CoreBluetooth prints it
            "0000180f-0000-1000-8000-00805f9b34fb", // battery, as Android prints it
            "6e400001-b5a3-f393-e0a9-e50e24dcca9e",
            ShearwaterBle.SERVICE_UUID.uppercase(),
        )
        assertEquals(
            listOf(ShearwaterBle.SERVICE_UUID.uppercase(), "6e400001-b5a3-f393-e0a9-e50e24dcca9e"),
            ShearwaterBle.serialServices(services) { it },
        )
        assertEquals(emptyList(), ShearwaterBle.serialServices(listOf("1800", "180F")) { it })
    }
}
