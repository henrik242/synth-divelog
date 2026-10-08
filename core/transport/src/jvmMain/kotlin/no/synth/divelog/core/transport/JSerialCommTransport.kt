package no.synth.divelog.core.transport

import com.fazecast.jSerialComm.SerialPort
import no.synth.divelog.core.divecomputer.transport.Parity
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportException

/**
 * Desktop wired serial [Transport] over jSerialComm, the JVM counterpart to the
 * Android [UsbSerialTransport]. The line discipline (DTR, the half-duplex RTS
 * turnaround, quiet gap, flush and echo discard) is [SerialLineTransport]'s.
 */
class JSerialCommTransport(
    port: SerialPort,
    params: SerialParams,
) : Transport by SerialLineTransport(JSerialCommLine(port, params), params) {
    companion object {
        /** System serial port names available to the desktop app, for a picker. */
        fun availablePortNames(): List<String> = SerialPort.getCommPorts().map { it.systemPortName }

        /** Open a port by its system name (e.g. "ttyUSB0", "cu.usbserial-...", "COM3"). */
        fun byName(name: String, params: SerialParams): JSerialCommTransport =
            JSerialCommTransport(SerialPort.getCommPort(name), params)
    }
}

private class JSerialCommLine(
    private val port: SerialPort,
    private val params: SerialParams,
) : JvmSerialLine() {
    override fun open() {
        port.setComPortParameters(params.baudRate, params.dataBits, stopBitsConst(params.stopBits), parityConst(params.parity))
        // Semi-blocking reads with blocking writes, set once and never changed. Reads honour
        // the caller's deadline by looping these short blocking reads, so nothing
        // reconfigures the port per read: tcsetattr on a half-duplex line delays and flushes
        // the port in the narrow window right after the RTS turnaround, losing the fast reply.
        port.setComPortTimeouts(
            SerialPort.TIMEOUT_READ_SEMI_BLOCKING or SerialPort.TIMEOUT_WRITE_BLOCKING,
            PORT_READ_TIMEOUT_MS,
            WRITE_TIMEOUT_MS,
        )
        if (!port.openPort()) throw TransportException("Could not open serial port ${port.systemPortName}")
    }

    override fun close() {
        port.closePort()
    }

    override fun setRts(on: Boolean) {
        if (on) port.setRTS() else port.clearRTS()
    }

    override fun setDtr(on: Boolean) {
        if (on) port.setDTR() else port.clearDTR()
    }

    override fun write(data: ByteArray) {
        // writeBytes usually returns once the bytes are on the wire.
        val written = port.writeBytes(data, data.size)
        if (written < data.size) throw TransportException("Short serial write: $written of ${data.size}")
    }

    // One semi-blocking read (up to PORT_READ_TIMEOUT_MS); the caller loops to its deadline.
    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        val tmp = ByteArray(length)
        val n = port.readBytes(tmp, length)
        if (n < 0) throw TransportException("Serial read error on ${port.systemPortName}")
        tmp.copyInto(buffer, offset, 0, n)
        return n
    }

    override fun flush() {
        port.flushIOBuffers()
    }

    private fun stopBitsConst(stopBits: Int): Int = when (stopBits) {
        2 -> SerialPort.TWO_STOP_BITS
        else -> SerialPort.ONE_STOP_BIT
    }

    private fun parityConst(parity: Parity): Int = when (parity) {
        Parity.NONE -> SerialPort.NO_PARITY
        Parity.ODD -> SerialPort.ODD_PARITY
        Parity.EVEN -> SerialPort.EVEN_PARITY
    }

    private companion object {
        const val WRITE_TIMEOUT_MS = 2_000
        const val PORT_READ_TIMEOUT_MS = 100
    }
}
