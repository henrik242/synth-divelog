package no.synth.divelog.core.transport

import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class PacketQueueTest {
    private val buf = ByteArray(10)

    @Test
    fun aReadReturnsBytesOfOnePacketOnly() {
        val q = PacketQueue()
        q.offer(byteArrayOf(1, 2, 3))
        q.offer(byteArrayOf(4))
        assertEquals(2, q.read(buf, 0, 2, 100))
        assertEquals(1, q.read(buf, 2, 8, 100)) // the rest of the first, not the second
        assertEquals(1, q.read(buf, 3, 7, 100))
        assertEquals(listOf<Byte>(1, 2, 3, 4), buf.take(4))
        assertFailsWith<TransportTimeoutException> { q.read(buf, 0, 10, 10) }
    }

    @Test
    fun theLinkEndingIsReportedAfterWhatCameBefore() {
        val q = PacketQueue()
        q.offer(byteArrayOf(7))
        q.end(TransportException("lost"))
        q.end(TransportException("later")) // the first reason wins
        assertEquals(1, q.read(buf, 0, 4, 100))
        assertEquals("lost", assertFailsWith<TransportException> { q.read(buf, 0, 4, 100) }.message)
        assertEquals("lost", assertFailsWith<TransportException> { q.read(buf, 0, 4, 100) }.message)
        q.close()
        assertFailsWith<TransportClosedException> { q.read(buf, 0, 4, 100) }
    }

    @Test
    fun clearForgetsAnEarlierConnection() {
        // Android's retry after status 133 reuses the queue.
        val q = PacketQueue()
        q.offer(byteArrayOf(1, 2, 3))
        q.read(buf, 0, 1, 100) // part of a packet left over
        q.end(TransportException("old"))
        q.clear()
        assertFailsWith<TransportTimeoutException> { q.read(buf, 0, 4, 10) }
        q.offer(byteArrayOf(9))
        assertEquals(1, q.read(buf, 0, 4, 100))
        assertEquals(9, buf[0])
    }
}
