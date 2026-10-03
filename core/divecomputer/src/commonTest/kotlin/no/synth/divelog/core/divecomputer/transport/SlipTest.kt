package no.synth.divelog.core.divecomputer.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SlipTest {
    @Test
    fun encodeTerminatesWithEndAndHasNoLeadingDelimiter() {
        val encoded = Slip.encode(byteArrayOf(0x01, 0x02))
        assertEquals(Slip.END, encoded.last().toInt() and 0xFF)
        assertEquals(0x01, encoded.first().toInt() and 0xFF) // first byte is payload, not END
    }

    @Test
    fun escapesEndAndEscBytes() {
        val payload = byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0x10)
        val encoded = Slip.encode(payload)
        // END -> ESC ESC_END, ESC -> ESC ESC_ESC, then a single trailing END.
        val expected = byteArrayOf(
            0xDB.toByte(), 0xDC.toByte(),
            0xDB.toByte(), 0xDD.toByte(),
            0x10,
            0xC0.toByte(),
        )
        assertContentEquals(expected, encoded)
    }

    @Test
    fun unescapeInvertsEscaping() {
        val payload = byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0x00, 0x7F, 0xDB.toByte(), 0xDC.toByte())
        val encoded = Slip.encode(payload)
        val inner = encoded.copyOfRange(0, encoded.size - 1) // drop trailing END
        assertContentEquals(payload, Slip.unescape(inner))
    }

    @Test
    fun frameChannelRoundTripsThroughReplay() {
        val payload = byteArrayOf(0x53, 0xC0.toByte(), 0x44, 0xDB.toByte())
        val recorded = Transcript(
            listOf(TransportEvent(Direction.READ, Slip.encode(payload))),
        )
        val channel = FrameChannel(ReplayTransport(recorded, strictWrites = false))
        assertContentEquals(payload, channel.readFrame())
    }

    @Test
    fun frameChannelSkipsLeadingDelimiters() {
        val payload = byteArrayOf(0x11, 0x22)
        val noise = byteArrayOf(Slip.END.toByte(), Slip.END.toByte()) + Slip.encode(payload)
        val channel = FrameChannel(
            ReplayTransport(Transcript(listOf(TransportEvent(Direction.READ, noise))), strictWrites = false),
        )
        assertContentEquals(payload, channel.readFrame())
    }
}
