package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException

/**
 * A test [Transport] that answers the Vyper2-family ReadMemory and GetVersion commands
 * from an in-memory image, validating framing and CRC the way a real device would.
 * Drives the protocol end to end without a serial line; the line echo is the
 * transport's business, so none is modelled.
 */
class FakeSuuntoVyper2Device(
    private val memory: ByteArray,
    private val version: ByteArray = byteArrayOf(0x15, 0x00, 0x01, 0x04),
) : Transport {
    /** Number of ReadMemory commands answered. */
    var memoryReads = 0
        private set

    private var out = ByteArray(0)
    private var outPos = 0

    override fun open() {}

    override fun write(data: ByteArray) {
        require(data.isNotEmpty()) { "empty command" }
        require(SuuntoCrc.xor(data, 0, data.size - 1) == data[data.size - 1]) { "bad command CRC" }
        when (data[0]) {
            0x05.toByte() -> {
                require(data.size == 7) { "expected a 7-byte read command, got ${data.size}" }
                val address = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
                val count = data[5].toInt() and 0xFF
                memoryReads++
                val reply = byteArrayOf(0x05, 0x00, (3 + count).toByte(), data[3], data[4], data[5]) +
                    memory.copyOfRange(address, address + count)
                out = SuuntoCrc.appended(reply)
            }
            0x0F.toByte() -> {
                out = SuuntoCrc.appended(byteArrayOf(0x0F, 0x00, 0x04) + version)
            }
            else -> throw IllegalArgumentException("unexpected command 0x${(data[0].toInt() and 0xFF).toString(16)}")
        }
        outPos = 0
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        if (outPos >= out.size) throw TransportTimeoutException()
        val n = minOf(length, out.size - outPos)
        out.copyInto(buffer, offset, outPos, outPos + n)
        outPos += n
        return n
    }

    override fun close() {}
}
