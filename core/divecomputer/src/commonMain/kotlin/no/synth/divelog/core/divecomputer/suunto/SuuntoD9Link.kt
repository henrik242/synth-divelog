package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException

/**
 * Command layer for the newer Suunto D9 family (HelO2, Vyper2, Cobra2/3, Vyper Air
 * and the D-series). A packet exchange at 9600 8N1; the line is half-duplex with RTS
 * direction control handled by the transport, not here (see SERIAL_PARAMS). Unlike
 * the old family the proper cable does not echo sent bytes.
 *
 * A request is `[command] [lenHi] [lenLo] [params...] [crc]`, where the 16-bit
 * length counts the parameter bytes and the CRC is the XOR of every preceding
 * byte. The reply echoes the command and the request framing, appends the
 * requested data, and ends with its own CRC:
 *
 *     ReadMemory  -> 05 00 03 addrHi addrLo count crc
 *                 <- 05 00 03 addrHi addrLo count <count bytes> crc   (count 1..0x78)
 *     GetVersion  -> 0F 00 00 crc
 *                 <- 0F 00 00 <4 version bytes> crc
 */
class SuuntoD9Link(
    private val transport: Transport,
    private val timeoutMs: Long = 3_000,
) {
    /** Read [count] bytes (1..[MAX_PAGE]) from the 16-bit [address]. */
    fun readMemory(address: Int, count: Int): ByteArray {
        require(count in 1..MAX_PAGE) { "D9 page read is 1..$MAX_PAGE bytes, got $count" }
        val params = byteArrayOf(
            ((address ushr 8) and 0xFF).toByte(),
            (address and 0xFF).toByte(),
            (count and 0xFF).toByte(),
        )
        val reply = exchange(CMD_READ, params, expectedDataLen = count)
        return reply.copyOfRange(reply.size - count, reply.size)
    }

    /** Read [count] bytes from [address], paging in [MAX_PAGE]-byte reads. */
    fun readRange(address: Int, count: Int): ByteArray {
        val out = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = minOf(MAX_PAGE, count - read)
            readMemory(address + read, n).copyInto(out, read)
            read += n
        }
        return out
    }

    /** Firmware version as four bytes: id, high, mid, low. */
    fun readVersion(): ByteArray = exchange(CMD_VERSION, ByteArray(0), expectedDataLen = VERSION_LEN)
        .let { it.copyOfRange(it.size - VERSION_LEN, it.size) }

    /**
     * Send a framed command and return the full reply payload (the echoed request
     * framing plus [expectedDataLen] data bytes, CRC validated and stripped).
     */
    private fun exchange(command: Byte, params: ByteArray, expectedDataLen: Int): ByteArray {
        val request = SuuntoCrc.appended(
            byteArrayOf(command, ((params.size ushr 8) and 0xFF).toByte(), (params.size and 0xFF).toByte()) + params,
        )
        // Reply = command + 2 length bytes + echoed params + data + crc.
        val replyLen = 3 + params.size + expectedDataLen + 1

        // The half-duplex turnaround on this cable is timing-sensitive and has no echo
        // to sync on, so a reply can be missed or clipped. Resend and reread, letting the
        // transport's write jitter vary the turnaround phase, until a reply validates.
        var lastError = "no reply"
        repeat(MAX_TURNAROUND_RETRIES) {
            transport.write(request)
            val reply = readReply(replyLen)
            when {
                reply == null -> lastError = "no reply"
                reply[0] != command -> lastError = "bad echo 0x${hex(reply[0])}"
                reply[reply.size - 1] != SuuntoCrc.xor(reply, 0, reply.size - 1) -> lastError = "CRC mismatch"
                else -> return reply.copyOfRange(0, reply.size - 1)
            }
            drainInput()
        }
        throw ProtocolException("D9: no valid reply to 0x${hex(command)} after $MAX_TURNAROUND_RETRIES tries ($lastError)")
    }

    /** Read a full [n]-byte reply, or null if it times out or stalls part way. */
    private fun readReply(n: Int): ByteArray? {
        val buffer = ByteArray(n)
        var got = 0
        while (got < n) {
            // A missed turnaround shows up as silence on the first byte; fail fast there
            // so a retry can try a new phase, but allow longer for the rest of the reply.
            val t = if (got == 0) FIRST_BYTE_TIMEOUT_MS else CHUNK_TIMEOUT_MS
            val r = try {
                transport.read(buffer, got, n - got, t)
            } catch (e: TransportTimeoutException) {
                return null
            }
            if (r <= 0) return null
            got += r
        }
        return buffer
    }

    /** Drop any leftover bytes so a partial or stale reply does not corrupt the next read. */
    private fun drainInput() {
        val scratch = ByteArray(64)
        while (true) {
            val r = try {
                transport.read(scratch, 0, scratch.size, DRAIN_TIMEOUT_MS)
            } catch (e: TransportTimeoutException) {
                return
            }
            if (r <= 0) return
        }
    }

    private fun hex(b: Byte): String = (b.toInt() and 0xFF).toString(16).padStart(2, '0')

    companion object {
        const val MAX_PAGE = 0x78 // 120 bytes, larger than the old family
        private const val VERSION_LEN = 4
        private const val CMD_READ = 0x05.toByte()
        private const val CMD_VERSION = 0x0F.toByte()
        private const val MAX_TURNAROUND_RETRIES = 60
        private const val FIRST_BYTE_TIMEOUT_MS = 200L
        private const val CHUNK_TIMEOUT_MS = 400L
        private const val DRAIN_TIMEOUT_MS = 50L
    }
}
