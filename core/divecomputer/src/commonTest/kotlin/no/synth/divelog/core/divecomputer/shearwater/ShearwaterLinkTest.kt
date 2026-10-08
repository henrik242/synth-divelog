package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.transport.Slip
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShearwaterLinkTest {
    /** Answers every frame with an exit reply and records the timeout of each read. */
    private class RecordingDevice : Transport {
        val readTimeouts = mutableListOf<Long>()
        private var pending = ByteArray(0)

        override fun open() {}
        override fun close() {}

        override fun write(data: ByteArray) {
            pending = Slip.encode(byteArrayOf(0x01, 0xFF.toByte(), 0x02, 0x00, 0x77))
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
            readTimeouts += timeoutMs
            if (pending.isEmpty()) throw TransportTimeoutException()
            val n = minOf(length, pending.size)
            pending.copyInto(buffer, offset, 0, n)
            pending = pending.copyOfRange(n, pending.size)
            return n
        }
    }

    @Test
    fun firstReplyGetsTheConnectTimeoutThenTheNormalOne() {
        val device = RecordingDevice()
        val link = ShearwaterLink(device, timeoutMs = 3_000)

        link.exchange(byteArrayOf(0x37))
        val first = device.readTimeouts.toList()
        device.readTimeouts.clear()
        link.exchange(byteArrayOf(0x37))

        assertTrue(first.isNotEmpty() && first.all { it == ShearwaterLink.CONNECT_TIMEOUT_MS }, "first: $first")
        assertEquals(setOf(3_000L), device.readTimeouts.toSet())
    }
}
