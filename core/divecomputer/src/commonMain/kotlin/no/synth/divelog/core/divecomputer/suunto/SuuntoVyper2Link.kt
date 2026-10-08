package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException

/**
 * Command layer for the Suunto Vyper2 family (HelO2, Vyper2, Cobra2/3, Vyper Air).
 * A packet exchange at 9600 8N1; the line is half-duplex with RTS
 * direction control handled by the transport, not here (see SERIAL_PARAMS).
 *
 * A request is `[command] [lenHi] [lenLo] [params...] [crc]`, where the 16-bit
 * length counts the parameter bytes and the CRC is the XOR of every preceding
 * byte. The reply repeats the command and the request parameters, appends the
 * requested data, and ends with its own CRC; its length field counts the
 * parameters plus the data:
 *
 *     ReadMemory  -> 05 00 03 addrHi addrLo count crc
 *                 <- 05 00 (3+count) addrHi addrLo count <count bytes> crc   (count 1..0x78)
 *     GetVersion  -> 0F 00 00 crc
 *                 <- 0F 00 04 <4 version bytes> crc
 */
class SuuntoVyper2Link(
    private val transport: Transport,
    private val timeoutMs: Long = 3_000,
) {
    /** Read [count] bytes (1..[MAX_PAGE]) from the 16-bit [address]. */
    fun readMemory(address: Int, count: Int): ByteArray {
        require(count in 1..MAX_PAGE) { "Vyper2 page read is 1..$MAX_PAGE bytes, got $count" }
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

        // The transport keeps the line quiet before each write and flushes stale input, so a
        // missed or clipped reply is rare; a few attempts cover it.
        var lastError = "no reply"
        repeat(MAX_TRIES) {
            transport.write(request)
            val reply = readReply(replyLen)
            lastError = when {
                reply == null -> "no reply"
                reply[0] != command -> "bad header 0x${hex(reply[0])}"
                (u16be(reply, 1) != replyLen - 4) -> "bad length ${u16be(reply, 1)}"
                // The reply repeats the request parameters (address and count for a read),
                // so a stale reply to another request cannot pass as this one.
                !params.indices.all { reply[3 + it] == params[it] } -> "parameter mismatch"
                reply[reply.size - 1] != SuuntoCrc.xor(reply, 0, reply.size - 1) -> "CRC mismatch"
                else -> return reply.copyOfRange(0, reply.size - 1)
            }
        }
        throw ProtocolException("Vyper2: no valid reply to 0x${hex(command)} after $MAX_TRIES tries ($lastError)")
    }

    /**
     * Read a full [n]-byte reply, or null if it times out. One generous window for the whole
     * reply (matching the raw capture path): the device can take a few hundred ms to answer,
     * and a short first-byte timeout here was missing those replies.
     */
    private fun readReply(n: Int): ByteArray? {
        val buffer = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = try {
                transport.read(buffer, got, n - got, REPLY_TIMEOUT_MS)
            } catch (e: TransportTimeoutException) {
                return null
            }
            if (r <= 0) return null
            got += r
        }
        return buffer
    }

    private fun u16be(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    private fun hex(b: Byte): String = (b.toInt() and 0xFF).toString(16).padStart(2, '0')

    companion object {
        const val MAX_PAGE = 0x78 // 120 bytes, larger than the old family
        private const val VERSION_LEN = 4
        private const val CMD_READ = 0x05.toByte()
        private const val CMD_VERSION = 0x0F.toByte()
        private const val MAX_TRIES = 4
        private const val REPLY_TIMEOUT_MS = 500L
    }
}
