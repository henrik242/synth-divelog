package no.synth.divelog.core.transport

import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Bluetooth Classic serial-port (SPP) [Transport] for desktop macOS. Opening a paired
 * device's `/dev/cu.*` node does not bring the Bluetooth link up on current macOS, so this
 * runs the bundled `rfcomm-bridge` helper, which opens the device's RFCOMM channel itself
 * and relays bytes over its stdin/stdout. The desktop counterpart of the Android
 * `BluetoothRfcommTransport`.
 */
class MacRfcommTransport(
    private val bridge: File,
    private val address: String,
) : Transport {
    @Volatile private var link: HelperLink? = null
    @Volatile private var stream: QueuedStream? = null
    @Volatile private var closed = false

    override fun open() {
        val l = startHelper(bridge, closed = { closed }, "connect", address).also { link = it }
        try {
            l.awaitConnected(address, CONNECT_TIMEOUT_MS)
        } catch (e: TransportException) {
            link = null
            throw e
        }
        stream = QueuedStream(l.process.inputStream, l.process.outputStream, address)
    }

    override fun write(data: ByteArray) {
        (stream ?: throw TransportClosedException()).write(data)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int =
        (stream ?: throw TransportClosedException()).read(buffer, offset, length, timeoutMs)

    override fun close() {
        closed = true
        stream?.close()
        stream = null
        link?.stop() // EOF on stdin makes the bridge close the channel
        link = null
    }

    /** A paired device that offers a serial-port service. */
    data class PairedDevice(val address: String, val name: String)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000L

        /** The bundled bridge helper, or null where there is none. */
        fun locateBridge(): File? = locateHelper("rfcomm-bridge", "rfcommBridge")

        /** Paired devices with a serial-port service, as the bridge reports them. */
        fun pairedDevices(bridge: File): List<PairedDevice> = runCatching {
            val p = ProcessBuilder(bridge.absolutePath, "list").start()
            val lines = p.inputStream.bufferedReader().readLines()
            p.waitFor(5, TimeUnit.SECONDS)
            lines.mapNotNull { line ->
                val parts = line.split('\t', limit = 2)
                if (parts.size == 2) PairedDevice(parts[0], parts[1]) else null
            }
        }.getOrDefault(emptyList())
    }
}
