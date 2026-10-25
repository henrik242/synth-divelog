package no.synth.divelog.core.transport

import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import java.io.DataInputStream
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Bluetooth LE [Transport] for desktop macOS: runs the bundled `ble-bridge` helper, which
 * drives the shared CoreBluetoothTransport and relays it as length-prefixed frames, so
 * packet boundaries survive the pipe (see BleBridge.kt). [id] is the CoreBluetooth
 * identifier [MacBleScanner] reports.
 */
class MacBleTransport(
    private val bridge: File,
    private val id: String,
) : Transport {
    private val notifications = PacketQueue()
    @Volatile private var link: HelperLink? = null
    @Volatile private var writeSize: Int? = null
    @Volatile private var closed = false

    override fun open() {
        val l = startHelper(bridge, closed = { closed }, "connect", id).also { link = it }
        writeSize = try {
            // A packet link always has a write size (a Shearwater picks its framing by it).
            l.awaitConnected("the dive computer", CONNECT_TIMEOUT_MS).toIntOrNull()
                ?: throw TransportException("The Bluetooth helper reported no write size")
        } catch (e: TransportException) {
            l.process.destroy()
            link = null
            throw e
        }
        Thread({ pump(l) }, "ble-bridge-reader").apply {
            isDaemon = true
            start()
        }
    }

    override val maxWriteSize: Int? get() = writeSize

    override fun write(data: ByteArray) {
        val l = link ?: throw TransportClosedException()
        try {
            l.process.outputStream.write(BridgeProtocol.frame(data))
            l.process.outputStream.flush()
        } catch (e: Exception) {
            throw TransportException(l.exitError() ?: "Write to the dive computer failed", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int =
        notifications.read(buffer, offset, length, timeoutMs)

    override fun close() {
        closed = true
        notifications.close()
        link?.stop()
        link = null
    }

    /**
     * One frame per notification: a BE16 length, then the bytes. When the helper goes, the
     * link ends after the last frame it wrote, with the reason it gave.
     */
    private fun pump(l: HelperLink) {
        val input = DataInputStream(l.process.inputStream)
        try {
            while (true) {
                val frame = ByteArray(input.readUnsignedShort()) // BridgeProtocol.frame's BE16 length
                input.readFully(frame)
                notifications.offer(frame)
            }
        } catch (_: Exception) {
            notifications.end(TransportException(l.exitError() ?: "Bluetooth helper exited"))
        }
    }

    private companion object {
        /** A backstop: the helper gives up on each connect step itself and says why. */
        const val CONNECT_TIMEOUT_MS = 5 * 60_000L
    }
}

/** The desktop [BleScanner] on macOS: each scan is the `ble-bridge` helper's `scan`, CoreBluetoothScanner's. */
class MacBleScanner(private val bridge: File) : BleScanner {
    private val seen = ConcurrentHashMap<String, ScannedDevice>()
    @Volatile private var process: Process? = null
    @Volatile private var problem: String? = null

    override fun found(): List<ScannedDevice> = byName(seen.values)

    override fun problem(): String? = problem

    val scanning: Boolean get() = process?.isAlive == true

    override fun scan() {
        if (scanning) return
        problem = null // the new scan reports it again if it still holds
        val p = runCatching { ProcessBuilder(bridge.absolutePath, "scan").start() }.getOrNull() ?: return
        process = p
        Thread({
            // runCatching: stop() kills the helper under the reader.
            runCatching { read(p) }
        }, "ble-bridge-scan").apply {
            isDaemon = true
            start()
        }
    }

    /** The scan's lines, until the helper exits. */
    private fun read(p: Process) {
        p.inputStream.bufferedReader().forEachLine { line ->
            when {
                line.startsWith(BridgeProtocol.DEVICE) -> {
                    val parts = line.removePrefix(BridgeProtocol.DEVICE).split('\t', limit = 2)
                    val name = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                    seen[parts[0]] = ScannedDevice(parts[0], name)
                }
                line.startsWith(BridgeProtocol.PROBLEM) ->
                    problem = line.removePrefix(BridgeProtocol.PROBLEM).trim().takeIf { it.isNotEmpty() }
            }
        }
    }

    override fun stop() {
        process?.destroy()
        process = null
    }

    companion object {
        /** The bundled helper, or null where there is none (not macOS, or not built). */
        fun locateBridge(): File? = locateHelper("ble-bridge", "bleBridge")
    }
}
