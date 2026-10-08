package no.synth.divelog.core.transport

import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import kotlin.concurrent.Volatile

/**
 * The primitives of one wired serial port, which each platform implements over its
 * serial library. [SerialLineTransport] builds the line discipline on top.
 */
interface SerialLine {
    /** Open the port with its line settings (baud, parity, stop bits) applied. */
    fun open()

    fun close()

    fun setRts(on: Boolean)

    fun setDtr(on: Boolean)

    /** Write all of [data], or throw. */
    fun write(data: ByteArray)

    /**
     * Read up to [length] bytes into [buffer] at [offset]. Returns 0 when nothing came in
     * a short wait; [timeoutMs] bounds that wait where the port supports it.
     */
    fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int

    /** Drop whatever is buffered on the line. */
    fun flush()

    fun nowMs(): Long

    fun sleep(ms: Long)
}

/**
 * A wired serial [Transport] over a platform [SerialLine]. It applies the protocol's
 * line discipline ([SerialParams]): DTR held for the session, and on a half-duplex line
 * a quiet gap before each write ([SerialParams.txIdleMs]), stale input flushed, RTS
 * flipped to transmit, a wait until the bytes have left the UART, RTS back to receive,
 * and the line echo dropped where the cable has one. The protocol layer then sees only
 * the device's reply.
 */
class SerialLineTransport(
    private val line: SerialLine,
    private val params: SerialParams,
) : Transport {
    @Volatile private var open = false
    private var lastActivityMs = 0L

    override fun open() {
        line.open()
        line.setDtr(params.dtr) // powers the Suunto interface; held for the session
        if (params.halfDuplex) line.setRts(!params.rtsTransmitHigh) // start in receive direction
        if (params.powerUpMs > 0) line.sleep(params.powerUpMs) // let the interface power up
        runCatching { line.flush() } // drop anything stale before the first command
        open = true
    }

    override fun write(data: ByteArray) {
        if (!open) throw TransportClosedException()
        if (params.txIdleMs > 0) {
            val wait = lastActivityMs + params.txIdleMs - line.nowMs()
            if (wait > 0) line.sleep(wait)
        }
        try {
            if (params.halfDuplex) writeTurnaround(data) else line.write(data)
        } finally {
            lastActivityMs = line.nowMs()
        }
    }

    private fun writeTurnaround(data: ByteArray) {
        runCatching { line.flush() } // drop stale or spurious bytes before this command
        line.setRts(params.rtsTransmitHigh) // drive the line to transmit
        val start = line.nowMs()
        line.write(data)
        // Switch as soon as the bytes are out: some devices reply within ~20 ms, and a late
        // switch garbles the start of the reply. A write that returns once the bytes are on
        // the wire makes this wait a no-op; a USB write returns as soon as the adapter has them.
        val drained = start + params.wireTimeMs(data.size) + TX_DRAIN_MARGIN_MS - line.nowMs()
        if (drained > 0) line.sleep(drained)
        if (params.txSettleMs > 0) line.sleep(params.txSettleMs)
        line.setRts(!params.rtsTransmitHigh) // switch the line to receive
        if (params.rxSettleMs > 0) line.sleep(params.rxSettleMs)
        if (params.discardsEcho) discardEcho(data.size)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        // Loop short port reads until the caller's deadline, so a byte returns at once and
        // closing the transport from another thread ends the wait.
        val deadline = line.nowMs() + timeoutMs.coerceAtLeast(1)
        while (true) {
            if (!open) throw TransportClosedException()
            val n = line.read(buffer, offset, length, (deadline - line.nowMs()).coerceAtLeast(1))
            if (n > 0) {
                lastActivityMs = line.nowMs()
                return n
            }
            if (line.nowMs() >= deadline) throw TransportTimeoutException()
        }
    }

    override fun close() {
        open = false
        runCatching { line.close() }
    }

    /** Drop the [count] bytes the line echoes; bounded so a short echo cannot hang. */
    private fun discardEcho(count: Int) {
        val scratch = ByteArray(count)
        var dropped = 0
        val deadline = line.nowMs() + ECHO_TIMEOUT_MS
        while (dropped < count && line.nowMs() < deadline) {
            val n = runCatching { line.read(scratch, 0, count - dropped, deadline - line.nowMs()) }.getOrDefault(0)
            if (n <= 0) break
            dropped += n
        }
    }

    private companion object {
        const val ECHO_TIMEOUT_MS = 500L
        const val TX_DRAIN_MARGIN_MS = 2L
    }
}
