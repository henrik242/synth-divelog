package no.synth.divelog.core.transport

import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import kotlin.concurrent.Volatile

/**
 * The notifications of a Bluetooth LE link, handed from the callback thread to a blocking
 * [read] that keeps packet boundaries: a read returns bytes of one notification only, and
 * what did not fit comes on the next read. The protocol's packet layer
 * (ShearwaterBlePackets) relies on that.
 *
 * When the link ends, [end] gives the reason later reads throw; [close] makes them throw
 * [TransportClosedException] instead, whichever came first.
 */
class NotificationQueue {
    private val packets = Channel<ByteArray>(Channel.UNLIMITED)
    private var leftover = END
    private var leftoverPos = 0

    @Volatile private var endReason: TransportException? = null
    @Volatile private var closed = false

    /** Queue one notification; called from the link's callback thread. */
    fun offer(packet: ByteArray) {
        if (packet.isNotEmpty()) packets.trySend(packet.copyOf())
    }

    /** The link dropped: reads throw [reason] once the queued notifications are read. */
    fun end(reason: TransportException) {
        if (endReason == null) endReason = reason
        packets.trySend(END)
    }

    /** The owner closed the link: reads throw [TransportClosedException], and a waiting one wakes. */
    fun close() {
        closed = true
        packets.trySend(END)
    }

    /** Drop what is queued from an earlier connection, for a fresh attempt. */
    fun clear() {
        while (packets.tryReceive().isSuccess) continue
        endReason = null
        leftover = END
        leftoverPos = 0
    }

    fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        if (closed) throw TransportClosedException()
        if (leftoverPos >= leftover.size) {
            val packet = runBlocking { withTimeoutOrNull(timeoutMs.coerceAtLeast(1)) { packets.receive() } }
                ?: throw TransportTimeoutException()
            if (packet.isEmpty()) {
                packets.trySend(END) // later reads see the end too
                if (closed) throw TransportClosedException()
                throw endReason ?: TransportClosedException()
            }
            leftover = packet
            leftoverPos = 0
        }
        val n = minOf(length, leftover.size - leftoverPos)
        leftover.copyInto(buffer, offset, leftoverPos, leftoverPos + n)
        leftoverPos += n
        return n
    }

    private companion object {
        /** Queued when the link ends; notifications are never empty. */
        val END = ByteArray(0)
    }
}
