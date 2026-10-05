package no.synth.divelog.ui.download

import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport

/** Which physical link a port rides, so the caller knows which transport [open] will use. */
enum class SerialPortKind { USB_SERIAL, BLUETOOTH_SPP }

/**
 * A port offered to the download picker: an [id], a human [label] and the [kind] of link
 * it rides. The [id] is opaque to the picker and encodes what the platform [SerialPorts]
 * needs to reopen the port in this session (it may change between sessions, e.g. a USB
 * adapter index). [descriptor] is the session-stable identity of the physical port, used
 * to remember a device's connection across sessions; it defaults to [id] where that is
 * already stable (a port name or a Bluetooth address).
 */
data class SerialPortInfo(
    val id: String,
    val label: String,
    val kind: SerialPortKind = SerialPortKind.USB_SERIAL,
    val descriptor: String = id,
)

/**
 * The platform serial layer the shared download runs over. Desktop lists jSerialComm
 * ports, Android lists USB-serial adapters and paired Bluetooth Classic SPP devices
 * (and requests the matching permission in [open]), iOS has none. [open] returns an
 * already-opened [Transport] ready for a protocol to drive; the caller closes it.
 *
 * Kept as an interface rather than an expect/actual class because the Android
 * implementation needs a Context at construction while the others take nothing;
 * each platform entry point builds the matching implementation and passes it in.
 */
interface SerialPorts {
    /** False where there is no wired serial path (iOS); the UI then shows it as unavailable. */
    val downloadSupported: Boolean

    fun list(): List<SerialPortInfo>

    /** Open [id] with [params] and return the live transport. Blocking; call off the main thread. */
    suspend fun open(id: String, params: SerialParams): Transport
}

/** No wired serial support: used on iOS and any platform without a serial path. */
class NoSerialPorts : SerialPorts {
    override val downloadSupported = false

    override fun list(): List<SerialPortInfo> = emptyList()

    override suspend fun open(id: String, params: SerialParams): Transport =
        throw UnsupportedOperationException("Wired download is not available on this platform")
}

/**
 * Preselect a port that looks like a USB-serial adapter, else the first one, else null.
 * The desktop lists every system port, so this skips the built-in ones where it can.
 */
fun preferredPort(ports: List<SerialPortInfo>): SerialPortInfo? =
    ports.firstOrNull {
        val n = it.label
        n.contains("usbserial", true) || n.contains("tty.usb", true) || n.contains("ttyUSB", true)
    } ?: ports.firstOrNull()
