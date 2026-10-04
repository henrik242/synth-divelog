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
        if (params.halfDuplex) {
            runCatching { port.flushIOBuffers() } // drop stale/spurious bytes before this command
            setRts(params.rtsTransmitHigh) // drive the line to transmit
            writeAll(data)
            if (params.echoSync) {
                // Read the sent bytes back as the turnaround sync: this blocks until the
                // command is physically on the wire, so the switch to receive is deterministic.
                readEcho(data.size)
                setRts(!params.rtsTransmitHigh) // switch the line to receive
            } else {
                val settle = params.txSettleMs + if (params.txJitterMs > 0) (0..params.txJitterMs).random() else 0
                if (settle > 0) sleep(settle)
                setRts(!params.rtsTransmitHigh) // switch the line to receive
                if (params.rxSettleMs > 0) sleep(params.rxSettleMs)
                if (params.discardsEcho) discardEcho(data.size)
            }
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

    /**
     * Read [count] echoed bytes back as the transmit-to-receive turnaround sync. Bounded by
     * a deadline so a missing or short echo cannot hang: if the cable does not reflect the
     * bytes under this transport, this returns after the timeout and we fall back to reading
     * the reply directly. Unlike [discardEcho] it keeps waiting across empty reads until the
     * deadline, because the echo is the signal we are blocking on, not noise to drop.
     */
    private fun readEcho(count: Int) {
        val scratch = ByteArray(count)
        var got = 0
        val deadline = nowMs() + ECHO_TIMEOUT_MS
        while (got < count && nowMs() < deadline) {
            val n = port.readBytes(scratch, count - got)
            if (n < 0) break
            got += n
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
        private const val PORT_READ_TIMEOUT_MS = 100

        /** System serial port names available to the desktop app, for a picker. */
        fun availablePortNames(): List<String> = SerialPort.getCommPorts().map { it.systemPortName }

        /** Open a port by its system name (e.g. "ttyUSB0", "cu.usbserial-...", "COM3"). */
        fun byName(name: String, params: SerialParams): JSerialCommTransport =
            JSerialCommTransport(SerialPort.getCommPort(name), params)
    }
}
