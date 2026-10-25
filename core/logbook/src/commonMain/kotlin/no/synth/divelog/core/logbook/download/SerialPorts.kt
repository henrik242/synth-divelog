package no.synth.divelog.core.logbook.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.transport.BleScanner

/**
 * Which physical link a port rides, so the caller knows which transport [open] will use.
 * A [BLUETOOTH_LE] port is a GATT packet link, not a byte stream: only a computer whose
 * protocol frames BLE packets can use it.
 */
enum class SerialPortKind { USB_SERIAL, BLUETOOTH_SPP, BLUETOOTH_LE }

/**
 * A port offered to the download picker: an [id], a human [label] and the [kind] of link
 * it rides, and for a scanned Bluetooth LE computer the [advertisedName]. The [id] is opaque to the picker and encodes what the platform [SerialPorts]
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
    val advertisedName: String? = null,
)

/**
 * The platform serial layer the shared download runs over. Desktop lists jSerialComm
 * ports, Android lists USB-serial adapters, paired Bluetooth Classic SPP devices and
 * Bluetooth LE dive computers found by a scan (and requests the matching permission in
 * [open]). macOS adds paired Bluetooth Classic devices and Bluetooth LE ones; iOS has
 * Bluetooth LE only. [open] returns an already-opened [Transport] ready for a
 * protocol to drive; the caller closes it.
 *
 * Kept as an interface rather than an expect/actual class because the Android
 * implementation needs a Context at construction while the others take nothing;
 * each platform entry point builds the matching implementation and passes it in.
 */
interface SerialPorts {
    /** False where nothing can be downloaded from; the UI then shows it as unavailable. */
    val downloadSupported: Boolean

    /** The kinds of port [list] can return; a computer needing another link is not offered. */
    val linkKinds: Set<SerialPortKind> get() = setOf(SerialPortKind.USB_SERIAL, SerialPortKind.BLUETOOTH_SPP)

    fun list(): List<SerialPortInfo>

    /**
     * Start, or keep running, the background search for computers found by scanning
     * ([SerialPortKind.BLUETOOTH_LE]); later [list] calls include what it found. The
     * picker calls it while such a computer is selected. No-op where nothing is scanned.
     */
    fun discover() {}

    /** Why a scan cannot find anything right now (Bluetooth off, permission missing), else null. */
    fun scanProblem(): String? = null

    /** Open [id] with [params] and return the live transport. Blocking; call off the main thread. */
    suspend fun open(id: String, params: SerialParams): Transport
}

/** No download support, for a platform without any link to a dive computer. */
class NoSerialPorts : SerialPorts {
    override val downloadSupported = false

    override fun list(): List<SerialPortInfo> = emptyList()

    override suspend fun open(id: String, params: SerialParams): Transport =
        throw UnsupportedOperationException("Download is not available on this platform")
}

/** The picker's ports for the Bluetooth LE computers [scanner] found, by id (an address or CoreBluetooth UUID). */
internal fun blePorts(scanner: BleScanner): List<SerialPortInfo> = scanner.found().map {
    val name = it.name?.takeIf(String::isNotBlank) // a blank name is none, as for the scan
    SerialPortInfo(
        id = BLE_PREFIX + it.id,
        label = "${name ?: it.id} (Bluetooth LE)",
        kind = SerialPortKind.BLUETOOTH_LE,
        advertisedName = name,
    )
}

/** Where [blePorts] put the computer's id. */
internal const val BLE_PREFIX = "ble:"

/**
 * Preselect a port that looks like a USB-serial adapter, else the first one, else null.
 * The desktop lists every system port, so this skips the built-in ones where it can.
 */
fun preferredPort(ports: List<SerialPortInfo>): SerialPortInfo? =
    ports.firstOrNull {
        val n = it.label
        n.contains("usbserial", true) || n.contains("tty.usb", true) || n.contains("ttyUSB", true)
    } ?: ports.firstOrNull()

/**
 * Run blocking [block] (a connect, a download) on the IO dispatcher. Cancelling the caller
 * closes [transport], which ends a wait that would otherwise block until its timeout.
 */
internal suspend fun <T> closingOnCancel(transport: Transport, block: () -> T): T = coroutineScope {
    val work = async(Dispatchers.IO) { block() }
    try {
        work.await()
    } catch (e: CancellationException) {
        transport.close()
        throw e
    }
}
