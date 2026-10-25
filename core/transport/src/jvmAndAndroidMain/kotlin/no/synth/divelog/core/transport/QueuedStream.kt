package no.synth.divelog.core.transport

import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import java.io.InputStream
import java.io.OutputStream
import kotlin.concurrent.Volatile

/**
 * Reads and writes a connected stream pair whose reads cannot time out on their own (an
 * RFCOMM socket, a helper process's stdout). A reader thread pumps [input] into a
 * [PacketQueue] and [read] waits on that with a deadline. When the stream ends, reads
 * report why: the pump's error, or a closed link.
 */
class QueuedStream(
    input: InputStream,
    private val output: OutputStream,
    private val name: String,
) {
    private val chunks = PacketQueue()
    @Volatile private var closed = false

    init {
        Thread({ pump(input) }, "$name-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun write(data: ByteArray) {
        if (closed) throw TransportClosedException()
        try {
            output.write(data)
            output.flush()
        } catch (e: Exception) {
            throw TransportException("Write to $name failed", e)
        }
    }

    fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int =
        chunks.read(buffer, offset, length, timeoutMs)

    /** Mark the stream closed and wake a waiting [read]; the owner closes the underlying link. */
    fun close() {
        closed = true
        chunks.close()
    }

    private fun pump(input: InputStream) {
        val buf = ByteArray(4_096)
        try {
            while (!closed) {
                val n = input.read(buf)
                if (n < 0) break
                chunks.offer(buf.copyOf(n))
            }
            chunks.end(TransportException("Connection to $name closed"))
        } catch (e: Throwable) {
            chunks.end(TransportException("Connection to $name lost: ${e.message}", e))
        }
    }
}
