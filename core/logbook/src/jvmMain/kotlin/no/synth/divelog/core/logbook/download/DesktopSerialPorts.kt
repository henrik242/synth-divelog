package no.synth.divelog.core.logbook.download

import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.transport.JSerialCommTransport
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
 * those nodes are hidden.
 */
class DesktopSerialPorts(
    private val rfcommBridge: File? = MacRfcommTransport.locateBridge(),
) : SerialPorts {
    override val downloadSupported = true

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
        return paired + wired
    }

    override suspend fun open(id: String, params: SerialParams): Transport {
        val bridge = rfcommBridge
        val transport = if (id.startsWith(BLUETOOTH_PREFIX) && bridge != null) {
            MacRfcommTransport(bridge, id.removePrefix(BLUETOOTH_PREFIX))
        } else {
            JSerialCommTransport.byName(id, params)
        }
        // The Bluetooth bridge can take up to 20 s to connect; cancelling closes it.
        return transport.also { closingOnCancel(it) { it.open() } }
    }

    /** macOS names a device's serial node after the device name with spaces and punctuation dropped. */
    private fun nodeName(name: String): String = name.filter { it.isLetterOrDigit() }.lowercase()

    private companion object {
        const val BLUETOOTH_PREFIX = "bt:"
    }
}
