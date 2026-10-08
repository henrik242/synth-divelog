package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DownloadCancelledException
import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.hex
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.u16be

/**
 * Reads device memory from the old Suunto Vyper family (Zoop, Vyper, Vytec,
 * Cobra, Gekko, Stinger, Mosquito) with the single paged read command.
 *
 * The wire exchange for one page (confirmed against the family protocol notes):
 *
 *     host   -> 05 addrHi addrLo count crc
 *     device -> 05 addrHi addrLo count <count bytes> crc
 *
 * `count` is 1..32, so any larger span is split into pages. The checksum is the
 * XOR of every preceding byte. The half-duplex RTS/DTR dance and discarding the
 * line echo are the transport's job ([SerialParams.halfDuplex]); this layer only
 * frames commands and validates replies.
 */
class SuuntoVyperMemory(
    private val transport: Transport,
    private val timeoutMs: Long = 3_000,
) {
    /** Read [count] bytes (1..32) from [address] in one command. */
    fun readPage(address: Int, count: Int): ByteArray {
        require(count in 1..MAX_PAGE) { "Vyper page read is 1..$MAX_PAGE bytes, got $count" }
        val command = SuuntoCrc.appended(
            byteArrayOf(
                CMD_READ,
                ((address ushr 8) and 0xFF).toByte(),
                (address and 0xFF).toByte(),
                (count and 0xFF).toByte(),
            ),
        )
        transport.write(command)

        // Reply: echo of the 4-byte command header, the data, then one CRC byte.
        val reply = readExact(HEADER + count + 1)
        if (reply[0] != CMD_READ) {
            throw ProtocolException("Vyper read: bad command echo 0x${hex(reply[0])}")
        }
        val echoedAddr = u16be(reply, 1)
        val echoedCount = reply[3].toInt() and 0xFF
        if (echoedAddr != address || echoedCount != count) {
            throw ProtocolException(
                "Vyper read: header mismatch, asked 0x${address.toString(16)}/$count " +
                    "got 0x${echoedAddr.toString(16)}/$echoedCount",
            )
        }
        val expectedCrc = SuuntoCrc.xor(reply, 0, reply.size - 1)
        if (reply[reply.size - 1] != expectedCrc) {
            throw ProtocolException("Vyper read: CRC mismatch at 0x${address.toString(16)}")
        }
        return reply.copyOfRange(HEADER, HEADER + count)
    }

    /**
     * Read [length] bytes starting at [address], paging in [MAX_PAGE]-byte reads.
     * Reports bytes-read progress and honours cancellation between pages.
     */
    fun readRange(
        address: Int,
        length: Int,
        onProgress: (read: Int, total: Int) -> Unit = { _, _ -> },
        cancel: CancellationSignal = CancellationSignal.NONE,
    ): ByteArray {
        val out = ByteArray(length)
        var read = 0
        while (read < length) {
            if (cancel.isCancelled()) throw DownloadCancelledException()
            val count = minOf(MAX_PAGE, length - read)
            readPage(address + read, count).copyInto(out, read)
            read += count
            onProgress(read, length)
        }
        return out
    }

    /** Read a single byte at [address]. */
    fun readByte(address: Int): Int = readPage(address, 1)[0].toInt() and 0xFF

    /** Read a big-endian 16-bit value at [address] (used for the write pointer). */
    fun readU16BE(address: Int): Int {
        return u16be(readPage(address, 2), 0)
    }

    private fun readExact(n: Int): ByteArray {
        val buffer = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = transport.read(buffer, got, n - got, timeoutMs)
            if (r <= 0) throw ProtocolException("Vyper read: stream ended after $got of $n bytes")
            got += r
        }
        return buffer
    }

    companion object {
        const val MAX_PAGE = 32
        private const val HEADER = 4
        private const val CMD_READ = 0x05.toByte()
    }
}
