package no.synth.divelog.core.transport

import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import java.io.File
import java.util.concurrent.LinkedBlockingQueue
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
    @Volatile private var process: Process? = null
    @Volatile private var stream: QueuedStream? = null

    // The bridge's status lines, while connecting; close() posts to it to abort the wait.
    private val status = LinkedBlockingQueue<String>()

    override fun open() {
        status.clear()
        val p = ProcessBuilder(bridge.absolutePath, "connect", address).start()
        process = p
        // The bridge reports one status line on stderr: CONNECTED, or ERROR and exits.
        Thread({ p.errorStream.bufferedReader().forEachLine { status.put(it) } }, "rfcomm-status").apply {
            isDaemon = true
            start()
        }
        val line = try {
            status.poll(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            null
        }
        if (line == null || !line.startsWith("CONNECTED")) {
            p.destroy()
            process = null
            if (line == CLOSED) throw TransportClosedException()
            val reason = line?.removePrefix("ERROR ") ?: "no answer within ${CONNECT_TIMEOUT_MS / 1000} s"
            throw TransportException("Could not connect to $address: $reason")
        }
        stream = QueuedStream(p.inputStream, p.outputStream, address)
    }

    override fun write(data: ByteArray) {
        (stream ?: throw TransportClosedException()).write(data)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int =
        (stream ?: throw TransportClosedException()).read(buffer, offset, length, timeoutMs)

    override fun close() {
        status.offer(CLOSED) // abort a connect in progress
        stream?.let {
            it.close()
            runCatching { process?.outputStream?.close() } // EOF on stdin makes the bridge close the channel
        }
        stream = null
        process?.let { p -> if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroy() }
        process = null
    }

    /** A paired device that offers a serial-port service. */
    data class PairedDevice(val address: String, val name: String)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private const val CLOSED = "CLOSED"

        /**
         * The bridge helper: the `synth.rfcommBridge` system property, else the app's
         * bundled resources. Null where there is none (not macOS, or not built).
         */
        fun locateBridge(): File? {
            val candidates = listOfNotNull(
                System.getProperty("synth.rfcommBridge")?.let(::File),
                System.getProperty("compose.application.resources.dir")?.let { File(it, "rfcomm-bridge") },
            )
            return candidates.firstOrNull { it.canExecute() }
        }

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
