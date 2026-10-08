package no.synth.divelog.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import java.util.UUID

/**
 * Bluetooth Classic serial (RFCOMM over the Serial Port Profile) for a paired
 * dive computer. The Android socket input stream cannot time out on its own, so
 * [QueuedStream] pumps it on a reader thread and reads wait with a deadline.
 * [close] from another thread also aborts a connect in progress.
 *
 * Requires the BLUETOOTH_CONNECT runtime permission (granted by the caller).
 */
class BluetoothRfcommTransport(
    private val device: BluetoothDevice,
    private val serviceUuid: UUID = SPP_UUID,
) : Transport {
    @Volatile private var socket: BluetoothSocket? = null
    @Volatile private var stream: QueuedStream? = null
    @Volatile private var closed = false

    override fun open() {
        closed = false
        // Prefer an insecure (unencrypted) RFCOMM link: older dive computers pair
        // with a fixed PIN and their weak radios drop an encrypted channel under
        // load. Fall back to a secure socket if the insecure connect is refused.
        val s = connectPreferInsecure()
        stream = QueuedStream(s.inputStream, s.outputStream, device.address)
    }

    @SuppressLint("MissingPermission")
    private fun connectPreferInsecure(): BluetoothSocket {
        try {
            return connect(device.createInsecureRfcommSocketToServiceRecord(serviceUuid))
        } catch (e: Exception) {
            if (closed) throw TransportClosedException()
        }
        try {
            return connect(device.createRfcommSocketToServiceRecord(serviceUuid))
        } catch (e: Exception) {
            if (closed) throw TransportClosedException()
            throw TransportException("Could not connect to ${device.address}", e)
        }
    }

    @SuppressLint("MissingPermission")
    private fun connect(s: BluetoothSocket): BluetoothSocket {
        socket = s
        try {
            s.connect()
            return s
        } catch (e: Exception) {
            runCatching { s.close() }
            throw e
        }
    }

    override fun write(data: ByteArray) {
        (stream ?: throw TransportClosedException()).write(data)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int =
        (stream ?: throw TransportClosedException()).read(buffer, offset, length, timeoutMs)

    override fun close() {
        closed = true
        stream?.close()
        runCatching { socket?.close() }
        socket = null
        stream = null
    }

    companion object {
        /** Well-known Bluetooth Serial Port Profile UUID. */
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }
}
