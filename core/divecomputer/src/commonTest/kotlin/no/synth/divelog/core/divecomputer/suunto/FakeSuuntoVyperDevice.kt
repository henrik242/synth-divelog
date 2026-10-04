package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException

/**
 * A test [Transport] that answers the old-Vyper read command from an in-memory
 * image, validating the command framing and CRC the way a real device would. This
 * drives the protocol end to end without a serial line; the half-duplex echo is
 * not modelled (the real transport discards it).
 */
class FakeSuuntoVyperDevice(private val memory: ByteArray) : Transport {
    private var out = ByteArray(0)
    private var outPos = 0

    override fun open() {}

    override fun write(data: ByteArray) {
        require(data.size == 5) { "expected a 5-byte read command, got ${data.size}" }
        require(data[0] == 0x05.toByte()) { "expected command 0x05" }
        require(SuuntoCrc.xor(data, 0, 4) == data[4]) { "bad command CRC" }
        val address = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
        val count = data[3].toInt() and 0xFF
        val reply = byteArrayOf(0x05, data[1], data[2], data[3]) +
            memory.copyOfRange(address, address + count)
        out = SuuntoCrc.appended(reply)
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
