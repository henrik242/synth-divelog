package no.synth.divelog.core.logbook.download

import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.transport.CoreBluetoothScanner
import no.synth.divelog.core.transport.CoreBluetoothTransport

/**
 * iOS has Bluetooth LE only (an app cannot reach Bluetooth Classic serial devices or USB
 * serial), so the Peregrine, Petrel 2 and Petrel 3 are the computers offered: found by
 * [CoreBluetoothScanner] and opened with [CoreBluetoothTransport].
 */
class IosSerialPorts : SerialPorts {
    private val ble = CoreBluetoothScanner()
    override val downloadSupported = true
    override val linkKinds: Set<SerialPortKind> = setOf(SerialPortKind.BLUETOOTH_LE)

    override fun list(): List<SerialPortInfo> = blePorts(ble)

    override fun discover() = ble.scan()

    override fun scanProblem(): String? = ble.problem()

    override suspend fun open(id: String, params: SerialParams): Transport {
        ble.stop()
        return CoreBluetoothTransport(id.removePrefix(BLE_PREFIX)).also { closingOnCancel(it) { it.open() } }
    }
}
