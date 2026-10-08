package no.synth.divelog.core.transport

import com.fazecast.jSerialComm.SerialPort
import no.synth.divelog.core.divecomputer.transport.Parity
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException

/**
 * Desktop wired serial [Transport] over jSerialComm, the JVM counterpart to the
 * Android [UsbSerialTransport]. Same contract: it applies the protocol's line
 * settings and, on a half-duplex line, holds DTR high, keeps the line quiet before
 * each write, flips RTS around it and discards the line echo where there is one.
 */
class JSerialCommTransport(
    private val port: SerialPort,
    private val params: SerialParams,
) : Transport {
    private var open = false
    private var lastActivityMs = 0L

    override fun open() {
        port.setComPortParameters(params.baudRate, params.dataBits, stopBitsConst(params.stopBits), parityConst(params.parity))
        // Semi-blocking reads with blocking writes, set once and never changed. read()
        // honours the caller's deadline by looping these short blocking reads, so nothing
        // reconfigures the port per read: tcsetattr on a half-duplex line delays and flushes
        // the port in the narrow window right after the RTS turnaround, losing the fast reply.
        port.setComPortTimeouts(
            SerialPort.TIMEOUT_READ_SEMI_BLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING,
            PORT_READ_TIMEOUT_MS,
            WRITE_TIMEOUT_MS,
        )
        if (!port.openPort()) throw TransportException("Could not open serial port ${port.systemPortName}")
        setDtr(params.dtr)
        if (params.halfDuplex) setRts(!params.rtsTransmitHigh) // start in receive direction
        if (params.powerUpMs > 0) sleep(params.powerUpMs) // let the interface power up
        runCatching { port.flushIOBuffers() } // drop anything stale before the first command
        open = true
    }

    override fun write(data: ByteArray) {
        if (!open) throw TransportClosedException()
        if (params.txIdleMs > 0) {
            val wait = lastActivityMs + params.txIdleMs - nowMs()
            if (wait > 0) sleep(wait)
        }
        try {
            writeTurnaround(data)
        } finally {
            lastActivityMs = nowMs()
        }
    }

    private fun writeTurnaround(data: ByteArray) {
        if (params.halfDuplex) {
            runCatching { port.flushIOBuffers() } // drop stale/spurious bytes before this command
            setRts(params.rtsTransmitHigh) // drive the line to transmit
            val start = nowMs()
            writeAll(data)
            // Switch as soon as the bytes are out: some devices reply within ~20 ms, and a
            // late switch garbles the start of the reply. writeBytes usually returns after
            // the bytes are on the wire already, so this wait is mostly a no-op.
            val drained = start + params.wireTimeMs(data.size) + TX_DRAIN_MARGIN_MS - nowMs()
            if (drained > 0) sleep(drained)
            if (params.txSettleMs > 0) sleep(params.txSettleMs)
            setRts(!params.rtsTransmitHigh) // switch the line to receive
            if (params.rxSettleMs > 0) sleep(params.rxSettleMs)
            if (params.discardsEcho) discardEcho(data.size)
        } else {
            writeAll(data)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        if (!open) throw TransportClosedException()
        val tmp = ByteArray(length)
        // The port is semi-blocking with a short fixed timeout; loop it until the caller's
        // deadline so a byte returns immediately but no tcsetattr runs between the RTS
        // turnaround and the read (which would lose the device's fast reply).
        val deadline = nowMs() + timeoutMs.coerceAtLeast(1)
        while (true) {
            val n = port.readBytes(tmp, length)
            if (n < 0) throw TransportException("Serial read error on ${port.systemPortName}")
            if (n > 0) {
                tmp.copyInto(buffer, offset, 0, n)
                lastActivityMs = nowMs()
                return n
            }
            if (nowMs() >= deadline) throw TransportTimeoutException()
        }
    }

    override fun close() {
        open = false
        runCatching { port.closePort() }
    }

    private fun writeAll(data: ByteArray) {
        val written = port.writeBytes(data, data.size)
        if (written < data.size) throw TransportException("Short serial write: $written of ${data.size}")
    }

    private fun discardEcho(count: Int) {
        val scratch = ByteArray(count)
        var dropped = 0
        val deadline = nowMs() + ECHO_TIMEOUT_MS
        while (dropped < count && nowMs() < deadline) {
            val n = port.readBytes(scratch, count - dropped)
            if (n <= 0) break
            dropped += n
        }
    }

    private fun nowMs(): Long = System.currentTimeMillis()

    private fun setRts(on: Boolean) {
        if (on) port.setRTS() else port.clearRTS()
    }

    private fun setDtr(on: Boolean) {
        if (on) port.setDTR() else port.clearDTR()
    }

    private fun sleep(ms: Long) = try {
        Thread.sleep(ms)
    } catch (e: InterruptedException) {
        Thread.currentThread().interrupt()
    }

    private fun stopBitsConst(stopBits: Int): Int = when (stopBits) {
        1 -> SerialPort.ONE_STOP_BIT
        2 -> SerialPort.TWO_STOP_BITS
        else -> SerialPort.ONE_STOP_BIT
    }

    private fun parityConst(parity: Parity): Int = when (parity) {
        Parity.NONE -> SerialPort.NO_PARITY
        Parity.ODD -> SerialPort.ODD_PARITY
        Parity.EVEN -> SerialPort.EVEN_PARITY
    }

    companion object {
        private const val ECHO_TIMEOUT_MS = 500L
        private const val TX_DRAIN_MARGIN_MS = 2L
        private const val WRITE_TIMEOUT_MS = 2_000
        private const val PORT_READ_TIMEOUT_MS = 100

        /** System serial port names available to the desktop app, for a picker. */
        fun availablePortNames(): List<String> = SerialPort.getCommPorts().map { it.systemPortName }

        /** Open a port by its system name (e.g. "ttyUSB0", "cu.usbserial-...", "COM3"). */
        fun byName(name: String, params: SerialParams): JSerialCommTransport =
            JSerialCommTransport(SerialPort.getCommPort(name), params)
    }
}
