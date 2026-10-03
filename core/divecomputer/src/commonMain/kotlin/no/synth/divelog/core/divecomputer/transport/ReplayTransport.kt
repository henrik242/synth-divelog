// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.divecomputer.transport

/**
 * Plays back a captured [Transcript] so protocol code can be tested without a
 * device. Reads serve the recorded READ bytes in order; a recorded timeout read
 * throws [TransportTimeoutException]. Writes are checked against recorded WRITE
 * bytes when [strictWrites] is set, which catches protocol regressions.
 */
class ReplayTransport(
    transcript: Transcript,
    private val strictWrites: Boolean = true,
) : Transport {
    private val events = transcript.events
    private var index = 0

    // Remaining bytes of the READ event currently being served.
    private var pending: ByteArray = ByteArray(0)
    private var pendingPos = 0

    private var closed = false

    override fun open() {
        closed = false
    }

    override fun write(data: ByteArray) {
        if (closed) throw TransportClosedException()
        if (!strictWrites) return // lenient: writes are not part of the fixture
        val event = nextEvent(Direction.WRITE)
            ?: throw TransportException("Unexpected write of ${data.size} bytes")
        if (!event.data.contentEquals(data)) {
            throw TransportException(
                "Write mismatch:\n  expected ${event.data.toHex()}\n  actual   ${data.toHex()}",
            )
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        if (closed) throw TransportClosedException()
        if (pendingPos >= pending.size) {
            val event = nextReadEvent() ?: throw TransportException("No more recorded data to read")
            if (event.timeout) throw TransportTimeoutException()
            pending = event.data
            pendingPos = 0
        }
        val n = minOf(length, pending.size - pendingPos)
        pending.copyInto(buffer, offset, pendingPos, pendingPos + n)
        pendingPos += n
        return n
    }

    override fun close() {
        closed = true
    }

    /** Advance to the next event of [direction], skipping nothing; returns null at the end. */
    private fun nextEvent(direction: Direction): TransportEvent? {
        if (index >= events.size) return null
        val event = events[index]
        require(event.direction == direction) {
            "Transcript expected a ${event.direction} but a $direction was performed"
        }
        index++
        return event
    }

    private fun nextReadEvent(): TransportEvent? = nextEvent(Direction.READ)
}
