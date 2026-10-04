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
 * settings and, for the half-duplex old Suunto Vyper family, holds DTR high, flips
 * RTS around each write and discards the line echo.
 */
class JSerialCommTransport(
    private val port: SerialPort,
    private val params: SerialParams,
) : Transport {
    private var open = false

    override fun open() {
        port.setComPortParameters(params.baudRate, params.dataBits, stopBitsConst(params.stopBits), parityConst(params.parity))
        // Non-blocking reads with blocking writes, set once. Read timeouts are done in
        // software (see read): reconfiguring the port per read runs tcsetattr, which on a
        // half-duplex line delays and flushes the port in the narrow window right after the
        // RTS turnaround, losing the device's fast reply.
        port.setComPortTimeouts(SerialPort.TIMEOUT_NONBLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING, 0, WRITE_TIMEOUT_MS)
        if (!port.openPort()) throw TransportException("Could not open serial port ${port.systemPortName}")
        setDtr(params.dtr)
        if (params.halfDuplex) setRts(!params.rtsTransmitHigh) // start in receive direction
        if (params.powerUpMs > 0) sleep(params.powerUpMs) // let the interface power up
        runCatching { port.flushIOBuffers() } // drop anything stale before the first command
        open = true
    }

    override fun write(data: ByteArray) {
        if (!open) throw TransportClosedException()
        if (params.halfDuplex) {
            setRts(params.rtsTransmitHigh) // drive the line to transmit
            writeAll(data)
            val settle = params.txSettleMs + if (params.txJitterMs > 0) (0..params.txJitterMs).random() else 0
            if (settle > 0) sleep(settle)
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
        // Software timeout: the port is non-blocking, so poll until bytes arrive or the
        // deadline passes. Nothing reconfigures the port here, so a read right after the
        // RTS turnaround starts listening immediately.
        val deadline = nowMs() + timeoutMs.coerceAtLeast(1)
        while (true) {
            val n = port.readBytes(tmp, length)
            if (n < 0) throw TransportException("Serial read error on ${port.systemPortName}")
            if (n > 0) {
                tmp.copyInto(buffer, offset, 0, n)
                return n
            }
            if (nowMs() >= deadline) throw TransportTimeoutException()
            sleep(POLL_INTERVAL_MS)
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
        while (dropped < count) {
            val n = port.readBytes(scratch, count - dropped)
            if (n < 0) break
            if (n == 0) {
                if (nowMs() >= deadline) break
                sleep(POLL_INTERVAL_MS)
                continue
            }
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
        private const val WRITE_TIMEOUT_MS = 2_000
        private const val POLL_INTERVAL_MS = 1L

        /** System serial port names available to the desktop app, for a picker. */
        fun availablePortNames(): List<String> = SerialPort.getCommPorts().map { it.systemPortName }

        /** Open a port by its system name (e.g. "ttyUSB0", "cu.usbserial-...", "COM3"). */
        fun byName(name: String, params: SerialParams): JSerialCommTransport =
            JSerialCommTransport(SerialPort.getCommPort(name), params)
    }
}
