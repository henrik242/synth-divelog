package no.synth.divelog.core.logbook.download

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Which ports and platforms each computer type is offered on. */
class DiveComputerTypeTest {
    private val usb = SerialPortInfo("usb:0", "cable", SerialPortKind.USB_SERIAL)
    private val spp = SerialPortInfo("bt:AA:BB:CC:DD:EE:01", "Petrel", SerialPortKind.BLUETOOTH_SPP)
    private val scanned = SerialPortInfo("ble:AA:BB:CC:DD:EE:02", "Peregrine TX", SerialPortKind.BLUETOOTH_LE, advertisedName = "Peregrine TX")
    private val all = listOf(usb, spp, scanned)
    private val serialOnly = setOf(SerialPortKind.USB_SERIAL, SerialPortKind.BLUETOOTH_SPP)

    private fun ports(type: DiveComputerType) = all.filter(type::canUse)

    @Test
    fun aSuuntoIsOfferedTheCableOnly() {
        assertEquals(listOf(usb), ports(DiveComputerType.SUUNTO_VYPER2))
    }

    @Test
    fun aPetrel1IsOfferedSerialPortsButNoScannedOne() {
        assertEquals(listOf(usb, spp), ports(DiveComputerType.SHEARWATER_PETREL))
    }

    @Test
    fun aNewerShearwaterIsOfferedEveryKindOfPort() {
        assertEquals(listOf(usb, spp, scanned), ports(DiveComputerType.SHEARWATER))
    }

    @Test
    fun onlyANewerShearwaterIsReachableWithBluetoothLeAlone() {
        val leOnly = setOf(SerialPortKind.BLUETOOTH_LE) // iOS
        assertTrue(DiveComputerType.SHEARWATER.reachableOver(leOnly))
        assertFalse(DiveComputerType.SHEARWATER_PETREL.reachableOver(leOnly))
        assertTrue(DiveComputerType.SHEARWATER_PETREL.reachableOver(serialOnly))
    }

    @Test
    fun aScanRunsOnlyWhereThereIsBluetoothLe() {
        assertTrue(DiveComputerType.SHEARWATER.foundByScan(serialOnly + SerialPortKind.BLUETOOTH_LE))
        assertFalse(DiveComputerType.SHEARWATER.foundByScan(serialOnly)) // Linux, Windows
    }
}
