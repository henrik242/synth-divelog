package no.synth.divelog.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Bluetooth Classic serial (RFCOMM over the Serial Port Profile) for a paired
 * dive computer. The Android socket input stream cannot time out on its own, so
 * a reader thread drains it into a queue and [read] waits with a deadline.
 *
 * Requires the BLUETOOTH_CONNECT runtime permission (granted by the caller).
 */
class BluetoothRfcommTransport(
    private val device: BluetoothDevice,
    private val serviceUuid: UUID = SPP_UUID,
) : Transport {
    private var socket: BluetoothSocket? = null
    private var output: OutputStream? = null
    private var reader: Thread? = null

    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var leftover: ByteArray = EMPTY
    private var leftoverPos = 0

    @Volatile private var readerError: Throwable? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    override fun open() {
        val s = device.createRfcommSocketToServiceRecord(serviceUuid)
        try {
            s.connect()
        } catch (e: Exception) {
            runCatching { s.close() }
            throw TransportException("Could not connect to ${device.address}", e)
        }
        socket = s
        output = s.outputStream
        val input = s.inputStream
        // Let the link settle, then discard anything left over from a previous
        // session so a fresh exchange starts clean.
        runCatching { Thread.sleep(SETTLE_MS) }
        runCatching {
            val available = input.available()
            if (available > 0) input.skip(available.toLong())
        }
        running = true
        reader = Thread({ pump(input) }, "rfcomm-reader").apply {
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
        } catch (e: Throwable) {
            if (running) readerError = e
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
            throw TransportException("Write failed", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        if (leftoverPos >= leftover.size) {
            readerError?.let { throw TransportException("Read failed", it) }
            val chunk = chunks.poll(timeoutMs, TimeUnit.MILLISECONDS)
                ?: run {
                    readerError?.let { throw TransportException("Read failed", it) }
                    if (!running && chunks.isEmpty()) throw TransportException("Connection closed")
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
        reader?.interrupt()
        runCatching { socket?.close() }
        socket = null
        output = null
    }

    companion object {
        /** Well-known Bluetooth Serial Port Profile UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        private const val SETTLE_MS = 300L
        private val EMPTY = ByteArray(0)
    }
}
