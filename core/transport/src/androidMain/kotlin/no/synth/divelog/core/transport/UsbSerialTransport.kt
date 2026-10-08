package no.synth.divelog.core.transport

import android.hardware.usb.UsbDeviceConnection
import com.hoho.android.usbserial.driver.UsbSerialPort
import no.synth.divelog.core.divecomputer.transport.Parity
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException

/**
 * A wired USB-serial [Transport] for Android USB host mode, backed by
 * usb-serial-for-android. It applies the protocol's line settings (baud, parity,
 * stop bits) and drives the line-control signals the half-duplex Suunto families need.
 *
 * Half-duplex handling ([SerialParams.halfDuplex]): DTR is held high the whole
 * session to power the interface; RTS flips the line direction. Each [write] waits
 * out [SerialParams.txIdleMs], sets RTS to transmit, lets the UART drain, clears RTS
 * to receive, and drops the bytes the line echoes back when [SerialParams.discardsEcho]
 * is set, so the protocol layer only sees the device's reply. A full-duplex line
 * leaves RTS alone and reads nothing back after a write.
 *
 * Construct it with an opened [UsbDeviceConnection] from `UsbManager` once the user
 * has granted the USB device permission; see [UsbSerialDevices].
 */
class UsbSerialTransport(
    private val connection: UsbDeviceConnection,
    private val port: UsbSerialPort,
    private val params: SerialParams,
) : Transport {
    private var open = false
    private var lastActivityMs = 0L

    override fun open() {
        port.open(connection)
        port.setParameters(params.baudRate, params.dataBits, stopBitsConst(params.stopBits), parityConst(params.parity))
        // DTR powers the Suunto interface and stays set for the whole session.
        port.dtr = params.dtr
        if (params.halfDuplex) port.rts = !params.rtsTransmitHigh // start in receive direction
        if (params.powerUpMs > 0) sleep(params.powerUpMs) // let the interface power up
        open = true
    }

    override fun write(data: ByteArray) {
        if (!open) throw TransportClosedException()
        if (params.txIdleMs > 0) {
            val wait = lastActivityMs + params.txIdleMs - System.currentTimeMillis()
            if (wait > 0) sleep(wait)
        }
        try {
            if (params.halfDuplex) {
                port.rts = params.rtsTransmitHigh // drive the line to transmit
                val start = System.currentTimeMillis()
                port.write(data, WRITE_TIMEOUT_MS)
                // The USB write returns once the adapter has the bytes, not once they are on
                // the wire; switch as soon as they are out, since a late switch garbles the
                // start of a fast reply.
                val drained = start + params.wireTimeMs(data.size) + TX_DRAIN_MARGIN_MS - System.currentTimeMillis()
                if (drained > 0) sleep(drained)
                if (params.txSettleMs > 0) sleep(params.txSettleMs)
                port.rts = !params.rtsTransmitHigh // switch the line to receive
                if (params.rxSettleMs > 0) sleep(params.rxSettleMs)
                if (params.discardsEcho) discardEcho(data.size)
            } else {
                port.write(data, WRITE_TIMEOUT_MS)
            }
        } catch (e: Exception) {
            throw TransportException("USB serial write failed", e)
        } finally {
            lastActivityMs = System.currentTimeMillis()
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        if (!open) throw TransportClosedException()
        val tmp = if (offset == 0 && length == buffer.size) buffer else ByteArray(length)
        val n = try {
            port.read(tmp, timeoutMs.toInt())
        } catch (e: Exception) {
            throw TransportException("USB serial read failed", e)
        }
        if (n <= 0) throw TransportTimeoutException()
        lastActivityMs = System.currentTimeMillis()
        if (tmp !== buffer) tmp.copyInto(buffer, offset, 0, n)
        return n
    }

    override fun close() {
        open = false
        runCatching { port.close() }
        runCatching { connection.close() }
    }

    /**
     * Drain and drop [count] echoed bytes a half-duplex line reflects after a write.
     * Bounded so a missing or short echo cannot hang the download.
     */
    private fun discardEcho(count: Int) {
        val scratch = ByteArray(count)
        var dropped = 0
        while (dropped < count) {
            val n = runCatching { port.read(scratch, ECHO_TIMEOUT_MS) }.getOrDefault(0)
            if (n <= 0) break
            dropped += n
        }
    }

    private fun sleep(ms: Long) = try {
        Thread.sleep(ms)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }

    private fun stopBitsConst(stopBits: Int): Int = when (stopBits) {
        1 -> UsbSerialPort.STOPBITS_1
        2 -> UsbSerialPort.STOPBITS_2
        else -> UsbSerialPort.STOPBITS_1
    }

    private fun parityConst(parity: Parity): Int = when (parity) {
        Parity.NONE -> UsbSerialPort.PARITY_NONE
        Parity.ODD -> UsbSerialPort.PARITY_ODD
        Parity.EVEN -> UsbSerialPort.PARITY_EVEN
    }

    companion object {
        private const val WRITE_TIMEOUT_MS = 2_000
        private const val ECHO_TIMEOUT_MS = 500
        private const val TX_DRAIN_MARGIN_MS = 2L
    }
}
