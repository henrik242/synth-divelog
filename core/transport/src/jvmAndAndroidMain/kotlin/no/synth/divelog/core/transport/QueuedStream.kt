package no.synth.divelog.core.transport

import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Reads and writes a connected stream pair whose reads cannot time out on their own (an
 * RFCOMM socket, a helper process's stdout). A reader thread pumps [input] into a queue
 * and [read] waits on that with a deadline. When the stream ends, reads report why: the
 * pump's error, or a closed link.
 */
class QueuedStream(
    input: InputStream,
    private val output: OutputStream,
    private val name: String,
) {
    private val chunks = LinkedBlockingQueue<ByteArray>()
    private var leftover = END
    private var leftoverPos = 0

    @Volatile private var pumpError: Throwable? = null
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

    fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        if (closed) throw TransportClosedException()
        if (leftoverPos >= leftover.size) {
            val chunk = chunks.poll(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                ?: throw TransportTimeoutException()
            if (chunk.isEmpty()) {
                chunks.put(END) // later reads see the end too
                if (closed) throw TransportClosedException()
                pumpError?.let { throw TransportException("Connection to $name lost: ${it.message}", it) }
                throw TransportException("Connection to $name closed")
            }
            leftover = chunk
            leftoverPos = 0
        }
        val n = minOf(length, leftover.size - leftoverPos)
        leftover.copyInto(buffer, offset, leftoverPos, leftoverPos + n)
        leftoverPos += n
        return n
    }

    /** Mark the stream closed and wake a waiting [read]; the owner closes the underlying link. */
    fun close() {
        closed = true
        chunks.put(END)
    }

    private fun pump(input: InputStream) {
        val buf = ByteArray(4_096)
        try {
            while (!closed) {
                val n = input.read(buf)
                if (n < 0) break
                if (n > 0) chunks.put(buf.copyOf(n))
            }
        } catch (e: Throwable) {
            if (!closed) pumpError = e
        } finally {
            chunks.put(END)
        }
    }

    private companion object {
        /** Queued when the stream ends; data chunks are never empty. */
        val END = ByteArray(0)
    }
}
