package no.synth.divelog.core.transport

import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** [PacketQueue] between a link's thread and a reader, as the transports use it. */
class PacketQueueThreadTest {
    @Test
    fun packetsArrivingAsReadsTimeOutAreNotLost() {
        val queue = PacketQueue()
        val count = 5_000
        val producer = thread {
            for (i in 0 until count) queue.offer(byteArrayOf((i shr 8).toByte(), i.toByte()))
        }
        val received = ArrayList<Int>()
        val buffer = ByteArray(2)
        while (received.size < count) {
            try {
                queue.read(buffer, 0, 2, timeoutMs = 1)
                received += (buffer[0].toInt() and 0xFF shl 8) or (buffer[1].toInt() and 0xFF)
            } catch (_: TransportTimeoutException) {
                continue
            }
        }
        producer.join()
        assertEquals((0 until count).toList(), received)
    }

    @Test
    fun closeWakesAReadWaitingOnAnotherThread() {
        val queue = PacketQueue()
        var failure: Throwable? = null
        val reader = thread { failure = runCatching { queue.read(ByteArray(1), 0, 1, timeoutMs = 10_000) }.exceptionOrNull() }
        Thread.sleep(100)
        queue.close()
        reader.join(1_000)
        assertIs<TransportClosedException>(failure)
    }
}
