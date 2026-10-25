package no.synth.divelog.core.logbook.download

import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.transport.JSerialCommTransport
import no.synth.divelog.core.transport.MacBleScanner
import no.synth.divelog.core.transport.MacBleTransport
import no.synth.divelog.core.transport.MacRfcommTransport
import java.io.File

/**
 * Desktop serial layer. Wired ports come from jSerialComm, except the macOS "tty.*"
 * dial-in nodes: they alias the matching "cu.*" call-up node but block on open waiting
 * for carrier detect, which jSerialComm does not clear. The port name is stable, so it
 * doubles as the descriptor remembered across sessions.
 *
 * On macOS, paired Bluetooth serial-port devices are listed through [rfcommBridge] and
 * opened with [MacRfcommTransport], since their "cu.*" nodes no longer bring the link up;
 * those nodes are hidden. Bluetooth LE computers come from a scan through [bleBridge]
 * ([MacBleScanner], started by [discover]) and are opened with [MacBleTransport].
 */
class DesktopSerialPorts(
    private val rfcommBridge: File? = MacRfcommTransport.locateBridge(),
    private val bleBridge: File? = MacBleScanner.locateBridge(),
) : SerialPorts {
    private val ble = bleBridge?.let(::MacBleScanner)
    override val downloadSupported = true

    override val linkKinds: Set<SerialPortKind> = buildSet {
        add(SerialPortKind.USB_SERIAL)
        add(SerialPortKind.BLUETOOTH_SPP)
        if (ble != null) add(SerialPortKind.BLUETOOTH_LE)
    }

    override fun discover() {
        ble?.scan()
    }

    override fun scanProblem(): String? = ble?.problem()

    override fun list(): List<SerialPortInfo> {
        val bluetooth = rfcommBridge?.let { MacRfcommTransport.pairedDevices(it) }.orEmpty()
        val bluetoothNodes = bluetooth.map { nodeName(it.name) }.toSet()
        val wired = JSerialCommTransport.availablePortNames()
            .filterNot { it.startsWith("tty.") || it.startsWith("/dev/tty.") }
            .filterNot { it.startsWith("cu.") && nodeName(it.removePrefix("cu.")) in bluetoothNodes }
            .map { SerialPortInfo(id = it, label = it) }
        val paired = bluetooth.map {
            SerialPortInfo(
                id = BLUETOOTH_PREFIX + it.address,
                label = "${it.name} (Bluetooth)",
                kind = SerialPortKind.BLUETOOTH_SPP,
                descriptor = it.address,
            )
        }
        val scanned = ble?.let(::blePorts).orEmpty()
        return paired + scanned + wired
    }

    override suspend fun open(id: String, params: SerialParams): Transport {
        ble?.stop()
        val transport = when {
            id.startsWith(BLUETOOTH_PREFIX) && rfcommBridge != null ->
                MacRfcommTransport(rfcommBridge, id.removePrefix(BLUETOOTH_PREFIX))
            id.startsWith(BLE_PREFIX) && bleBridge != null -> MacBleTransport(bleBridge, id.removePrefix(BLE_PREFIX))
            else -> JSerialCommTransport.byName(id, params)
        }
        // A Bluetooth bridge can take a while to connect; cancelling closes it.
        return transport.also { closingOnCancel(it) { it.open() } }
    }

    /** macOS names a device's serial node after the device name with spaces and punctuation dropped. */
    private fun nodeName(name: String): String = name.filter { it.isLetterOrDigit() }.lowercase()

    private companion object {
        const val BLUETOOTH_PREFIX = "bt:"
    }
}
