package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.transport.Direction
import no.synth.divelog.core.divecomputer.transport.ReplayTransport
import no.synth.divelog.core.divecomputer.transport.Transcript
import no.synth.divelog.core.divecomputer.transport.TransportEvent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Checks the Vyper2 (HelO2) packet framing and CRC on both directions with a fixed
 * transcript: the request bytes must match exactly (strict writes) and the reply
 * is decoded into the data payload.
 */
class SuuntoVyper2LinkTest {
    @Test
    fun readMemoryFramesTheRequestAndDecodesTheReply() {
        // host -> 05 00 03 01 00 04 crc ; device -> 05 00 07 01 00 04 <4 bytes> crc
        val request = SuuntoCrc.appended(byteArrayOf(0x05, 0x00, 0x03, 0x01, 0x00, 0x04))
        val payload = byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val reply = SuuntoCrc.appended(byteArrayOf(0x05, 0x00, 0x07, 0x01, 0x00, 0x04) + payload)
        val transcript = Transcript(
            listOf(
                TransportEvent(Direction.WRITE, request),
                TransportEvent(Direction.READ, reply),
            ),
        )
        val link = SuuntoVyper2Link(ReplayTransport(transcript, strictWrites = true))
        assertContentEquals(payload, link.readMemory(address = 0x0100, count = 4))
    }

    @Test
    fun readVersionFramesAZeroParamRequest() {
        // host -> 0F 00 00 crc ; device -> 0F 00 04 <id hi mid lo> crc
        val request = SuuntoCrc.appended(byteArrayOf(0x0F, 0x00, 0x00))
        val version = byteArrayOf(0x14, 0x01, 0x02, 0x03)
        val reply = SuuntoCrc.appended(byteArrayOf(0x0F, 0x00, 0x04) + version)
        val transcript = Transcript(
            listOf(
                TransportEvent(Direction.WRITE, request),
                TransportEvent(Direction.READ, reply),
            ),
        )
        val link = SuuntoVyper2Link(ReplayTransport(transcript, strictWrites = true))
        assertContentEquals(version, link.readVersion())
    }

    @Test
    fun readsAFullPageThenAShortOne() {
        val page = SuuntoVyper2Link.MAX_PAGE
        val events = mutableListOf<TransportEvent>()
        val expected = ByteArray(page + 5) { it.toByte() }
        var addr = 0
        var read = 0
        while (read < expected.size) {
            val count = minOf(page, expected.size - read)
            val req = SuuntoCrc.appended(
                byteArrayOf(0x05, 0x00, 0x03, ((addr ushr 8) and 0xFF).toByte(), (addr and 0xFF).toByte(), count.toByte()),
            )
            val data = expected.copyOfRange(read, read + count)
            val rsp = SuuntoCrc.appended(
                byteArrayOf(0x05, 0x00, (3 + count).toByte(), ((addr ushr 8) and 0xFF).toByte(), (addr and 0xFF).toByte(), count.toByte()) + data,
            )
            events += TransportEvent(Direction.WRITE, req)
            events += TransportEvent(Direction.READ, rsp)
            read += count
            addr += count
        }
        val link = SuuntoVyper2Link(ReplayTransport(Transcript(events), strictWrites = true))
        assertContentEquals(expected, link.readMemory(0, page) + link.readMemory(page, expected.size - page))
        assertEquals(page + 5, expected.size)
    }

    @Test
    fun rejectsAReplyForAnotherAddress() {
        // A stale reply to 0x0200 with a valid CRC must not pass as the read of 0x0100.
        val request = SuuntoCrc.appended(byteArrayOf(0x05, 0x00, 0x03, 0x01, 0x00, 0x04))
        val payload = byteArrayOf(1, 2, 3, 4)
        val stale = SuuntoCrc.appended(byteArrayOf(0x05, 0x00, 0x07, 0x02, 0x00, 0x04) + payload)
        val good = SuuntoCrc.appended(byteArrayOf(0x05, 0x00, 0x07, 0x01, 0x00, 0x04) + payload)
        val transcript = Transcript(
            listOf(
                TransportEvent(Direction.WRITE, request),
                TransportEvent(Direction.READ, stale),
                TransportEvent(Direction.WRITE, request),
                TransportEvent(Direction.READ, good),
            ),
        )
        val link = SuuntoVyper2Link(ReplayTransport(transcript, strictWrites = true))
        assertContentEquals(payload, link.readMemory(address = 0x0100, count = 4))
    }
}
