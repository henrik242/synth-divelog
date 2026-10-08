package no.synth.divelog.core.transport

import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import java.io.File
import java.io.InputStream
import java.io.OutputStream
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
    private var process: Process? = null
    private var output: OutputStream? = null
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var leftover: ByteArray = EMPTY
    private var leftoverPos = 0

    @Volatile private var running = false

    override fun open() {
        val p = ProcessBuilder(bridge.absolutePath, "connect", address).start()
        process = p
        // The bridge reports one status line on stderr: CONNECTED, or ERROR and exits.
        val status = LinkedBlockingQueue<String>()
        Thread({ p.errorStream.bufferedReader().forEachLine { status.put(it) } }, "rfcomm-status").apply {
            isDaemon = true
            start()
        }
        val line = status.poll(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (line == null || !line.startsWith("CONNECTED")) {
            p.destroy()
            val reason = line?.removePrefix("ERROR ") ?: "no answer within ${CONNECT_TIMEOUT_MS / 1000} s"
            throw TransportException("Could not connect to $address: $reason")
        }
        output = p.outputStream
        running = true
        Thread({ pump(p.inputStream) }, "rfcomm-reader").apply {
            isDaemon = true
            start()
        }
    }

    private fun pump(input: InputStream) {
        val buf = ByteArray(4_096)
        try {
            while (running) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) chunks.put(buf.copyOf(n))
            }
        } catch (e: Exception) {
            // The bridge exited; read() reports the closed link.
        } finally {
            running = false
        }
    }

    override fun write(data: ByteArray) {
        val out = output ?: throw TransportClosedException()
        try {
            out.write(data)
            out.flush()
        } catch (e: Exception) {
            throw TransportException("Write to $address failed", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        if (leftoverPos >= leftover.size) {
            val chunk = chunks.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: run {
                    if (!running && chunks.isEmpty()) throw TransportException("Connection to $address closed")
                    throw TransportTimeoutException()
                }
            leftover = chunk
            leftoverPos = 0
        }
        val n = minOf(length, leftover.size - leftoverPos)
        leftover.copyInto(buffer, offset, leftoverPos, leftoverPos + n)
        leftoverPos += n
        return n
    }

    override fun close() {
        running = false
        runCatching { output?.close() } // EOF on stdin makes the bridge close the channel
        output = null
        process?.let { p -> if (!p.waitFor(2, TimeUnit.SECONDS)) p.destroy() }
        process = null
    }

    /** A paired device that offers a serial-port service. */
    data class PairedDevice(val address: String, val name: String)

    companion object {
        private const val CONNECT_TIMEOUT_MS = 20_000L
        private val EMPTY = ByteArray(0)

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
