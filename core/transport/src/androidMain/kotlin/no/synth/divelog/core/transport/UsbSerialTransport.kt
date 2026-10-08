package no.synth.divelog.core.transport

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import no.synth.divelog.core.divecomputer.transport.Parity
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportException

/**
 * A wired USB-serial [Transport] for Android USB host mode, backed by
 * usb-serial-for-android. The line discipline (DTR, the half-duplex RTS turnaround,
 * quiet gap, flush and echo discard) is [SerialLineTransport]'s, shared with the
 * desktop [JSerialCommTransport].
 *
 * Construct it with an opened [UsbDeviceConnection] from `UsbManager` once the user
 * has granted the USB device permission; see [UsbSerialDevices].
 */
class UsbSerialTransport(
    connection: UsbDeviceConnection,
    port: UsbSerialPort,
    params: SerialParams,
) : Transport by SerialLineTransport(UsbSerialLine(connection, port, params), params)

private class UsbSerialLine(
    private val connection: UsbDeviceConnection,
    private val port: UsbSerialPort,
    private val params: SerialParams,
) : JvmSerialLine() {
    // usb-serial-for-android can lose data when a read buffer is smaller than the endpoint's
    // packet, so reads fill a whole-packet buffer and serve the caller from it.
    private var packet = ByteArray(MIN_PACKET)
    private var pending = 0
    private var pendingPos = 0

    override fun open() {
        port.open(connection)
        port.setParameters(params.baudRate, params.dataBits, stopBitsConst(params.stopBits), parityConst(params.parity))
        packet = ByteArray(maxOf(port.readEndpoint?.maxPacketSize ?: 0, MIN_PACKET))
    }

    override fun close() {
        runCatching { port.close() }
        runCatching { connection.close() }
    }

    override fun setRts(on: Boolean) {
        port.rts = on
    }

    override fun setDtr(on: Boolean) {
        port.dtr = on
    }

    override fun write(data: ByteArray) {
        try {
            port.write(data, WRITE_TIMEOUT_MS)
        } catch (e: Exception) {
            throw TransportException("USB serial write failed", e)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        if (pendingPos >= pending) {
            // A timeout of 0 would wait forever.
            val n = try {
                port.read(packet, timeoutMs.coerceIn(1, Int.MAX_VALUE.toLong()).toInt())
            } catch (e: Exception) {
                throw TransportException("USB serial read failed", e)
            }
            if (n <= 0) return 0
            pending = n
            pendingPos = 0
        }
        val n = minOf(length, pending - pendingPos)
        packet.copyInto(buffer, offset, pendingPos, pendingPos + n)
        pendingPos += n
        return n
    }

    override fun flush() {
        pending = 0
        pendingPos = 0
        port.purgeHwBuffers(true, true) // not every chip supports it; the caller tolerates that
    }

    private fun stopBitsConst(stopBits: Int): Int = when (stopBits) {
        2 -> UsbSerialPort.STOPBITS_2
        else -> UsbSerialPort.STOPBITS_1
    }

    private fun parityConst(parity: Parity): Int = when (parity) {
        Parity.NONE -> UsbSerialPort.PARITY_NONE
        Parity.ODD -> UsbSerialPort.PARITY_ODD
        Parity.EVEN -> UsbSerialPort.PARITY_EVEN
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 2_000
        const val MIN_PACKET = 64
    }
}
