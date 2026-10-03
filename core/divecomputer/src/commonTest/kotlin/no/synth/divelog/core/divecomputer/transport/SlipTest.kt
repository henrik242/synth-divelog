// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.divecomputer.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class SlipTest {
    @Test
    fun encodeWrapsInEndDelimiters() {
        val encoded = Slip.encode(byteArrayOf(0x01, 0x02))
        assertEquals(Slip.END, encoded.first().toInt() and 0xFF)
        assertEquals(Slip.END, encoded.last().toInt() and 0xFF)
    }

    @Test
    fun escapesEndAndEscBytes() {
        val payload = byteArrayOf(0xC0.toByte(), 0xDB.toByte(), 0x10)
        val encoded = Slip.encode(payload)
        // END -> ESC ESC_END, ESC -> ESC ESC_ESC, plus the two delimiters.
        val expected = byteArrayOf(
            0xC0.toByte(),
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
        val inner = encoded.copyOfRange(1, encoded.size - 1)
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
