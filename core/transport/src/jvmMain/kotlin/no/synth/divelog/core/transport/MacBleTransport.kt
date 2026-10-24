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
    private val notifications = NotificationQueue()
    @Volatile private var link: HelperLink? = null
    @Volatile private var writeSize: Int? = null

    override fun open() {
        val l = HelperLink(bridge, "connect", id)
        link = l
        writeSize = l.awaitConnected("the dive computer", CONNECT_TIMEOUT_MS).toIntOrNull()
        Thread({ pump(l) }, "ble-bridge-reader").apply {
            isDaemon = true
            start()
        }
    }

    override val maxWriteSize: Int? get() = writeSize

    override fun write(data: ByteArray) {
        val out = (link ?: throw TransportClosedException()).process.outputStream
        try {
            out.write(byteArrayOf((data.size shr 8).toByte(), data.size.toByte()) + data)
            out.flush()
        } catch (e: Exception) {
            throw TransportException("Write to the dive computer failed", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int =
        notifications.read(buffer, offset, length, timeoutMs)

    override fun close() {
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
                val frame = ByteArray(input.readUnsignedShort())
                input.readFully(frame)
                notifications.offer(frame)
            }
        } catch (_: Exception) {
            notifications.end(TransportException(l.exitError() ?: "Bluetooth helper exited"))
        }
    }

    private companion object {
        /** A scan for an uncached device, the connect, and time to confirm a pairing dialog. */
        const val CONNECT_TIMEOUT_MS = 90_000L
    }
}

/**
 * Finds Peregrines through the `ble-bridge` helper's `scan` (which runs the shared
 * CoreBluetoothScanner for 20 s): [scan] starts one unless one is running, and [found]
 * lists what scans have seen.
 */
class MacBleScanner(private val bridge: File) {
    private val seen = ConcurrentHashMap<String, ScannedDevice>()
    @Volatile private var process: Process? = null
    @Volatile private var problem: String? = null

    fun found(): List<ScannedDevice> = seen.values.sortedBy { it.name ?: it.id }

    /** Why a scan cannot find anything right now, worded for the picker, or null. */
    fun problem(): String? = problem

    fun scan() {
        if (process?.isAlive == true) return
        problem = null // the new scan reports it again if it still holds
        val p = runCatching { ProcessBuilder(bridge.absolutePath, "scan").start() }.getOrNull() ?: return
        process = p
        Thread({
            p.inputStream.bufferedReader().forEachLine { line ->
                when {
                    line.startsWith("DEVICE ") -> {
                        val parts = line.removePrefix("DEVICE ").split('\t', limit = 2)
                        val name = parts.getOrNull(1)?.takeIf { it.isNotEmpty() }
                        seen[parts[0]] = ScannedDevice(parts[0], name)
                    }
                    line.startsWith("PROBLEM") -> problem = line.removePrefix("PROBLEM").trim().takeIf { it.isNotEmpty() }
                }
            }
        }, "ble-bridge-scan").apply {
            isDaemon = true
            start()
        }
    }

    /** End a scan in progress; a scan slows down a connect. */
    fun stop() {
        process?.destroy()
        process = null
    }

    companion object {
        /** The bundled helper, or null where there is none (not macOS, or not built). */
        fun locateBridge(): File? = MacHelper.locate("ble-bridge", "bleBridge")
    }
}
