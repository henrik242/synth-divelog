package no.synth.divelog.core.logbook.download

import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.transport.CoreBluetoothScanner
import no.synth.divelog.core.transport.CoreBluetoothTransport

/**
 * iOS has Bluetooth LE only (an app cannot reach Bluetooth Classic serial devices or USB
 * serial), so the newer Shearwaters (Perdix, Teric, Peregrine, Petrel 2+) are offered: found by
 * [CoreBluetoothScanner] and opened with [CoreBluetoothTransport].
 */
class IosSerialPorts : SerialPorts {
    override val bleScanner = CoreBluetoothScanner()
    override val downloadSupported = true
    override val linkKinds: Set<SerialPortKind> = setOf(SerialPortKind.BLUETOOTH_LE)

    override fun list(): List<SerialPortInfo> = blePorts(bleScanner.found())

    override suspend fun transport(id: String, params: SerialParams): Transport = CoreBluetoothTransport(id.removePrefix(BLE_PREFIX))
}
