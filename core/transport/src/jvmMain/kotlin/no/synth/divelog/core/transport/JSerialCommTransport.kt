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
        applyReadTimeout(DEFAULT_READ_TIMEOUT_MS)
        if (!port.openPort()) throw TransportException("Could not open serial port ${port.systemPortName}")
        setDtr(params.dtr)
        if (params.halfDuplex) setRts(false) // start in receive direction
        open = true
    }

    override fun write(data: ByteArray) {
        if (!open) throw TransportClosedException()
        if (params.halfDuplex) {
            setRts(true) // drive the line to transmit
            writeAll(data)
            if (params.txSettleMs > 0) sleep(params.txSettleMs)
            setRts(false) // release the line to receive
            if (params.rxSettleMs > 0) sleep(params.rxSettleMs)
            discardEcho(data.size)
        } else {
            writeAll(data)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        if (!open) throw TransportClosedException()
        applyReadTimeout(timeoutMs.toInt())
        val tmp = ByteArray(length)
        val n = port.readBytes(tmp, length)
        if (n <= 0) throw TransportTimeoutException()
        tmp.copyInto(buffer, offset, 0, n)
        return n
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
        applyReadTimeout(ECHO_TIMEOUT_MS)
        val scratch = ByteArray(count)
        var dropped = 0
        while (dropped < count) {
            val n = port.readBytes(scratch, count - dropped)
            if (n <= 0) break
            dropped += n
        }
    }

    private fun applyReadTimeout(ms: Int) {
        port.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, ms.coerceAtLeast(1), 0)
    }

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
        private const val DEFAULT_READ_TIMEOUT_MS = 3_000
        private const val ECHO_TIMEOUT_MS = 500

        /** System serial port names available to the desktop app, for a picker. */
        fun availablePortNames(): List<String> = SerialPort.getCommPorts().map { it.systemPortName }

        /** Open a port by its system name (e.g. "ttyUSB0", "cu.usbserial-...", "COM3"). */
        fun byName(name: String, params: SerialParams): JSerialCommTransport =
            JSerialCommTransport(SerialPort.getCommPort(name), params)
    }
}
