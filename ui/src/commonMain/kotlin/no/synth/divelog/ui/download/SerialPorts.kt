package no.synth.divelog.ui.download

import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport

/** A serial/USB port offered to the download picker: a stable [id] and a human [label]. */
data class SerialPortInfo(val id: String, val label: String)

/**
 * The platform serial layer the shared download runs over. Desktop lists jSerialComm
 * ports, Android lists USB-serial adapters (and requests the USB permission in
 * [open]), iOS has none. [open] returns an already-opened [Transport] ready for a
 * protocol to drive; the caller closes it.
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
