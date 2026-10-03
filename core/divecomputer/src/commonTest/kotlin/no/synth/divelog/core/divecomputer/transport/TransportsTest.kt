// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.divecomputer.transport

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TransportsTest {
    @Test
    fun transcriptTextRoundTrip() {
        val original = Transcript(
            listOf(
                TransportEvent(Direction.WRITE, byteArrayOf(0x01, 0xAB.toByte()), atMillis = 5),
                TransportEvent(Direction.READ, byteArrayOf(0xC0.toByte(), 0x00), atMillis = 12),
                TransportEvent(Direction.READ, ByteArray(0), atMillis = 20, timeout = true),
            ),
        )
        val parsed = Transcript.fromText(original.toText())
        assertEquals(original.events, parsed.events)
    }

    @Test
    fun transcriptIgnoresBlankAndCommentLines() {
        val text = """
            # a capture
            W 0102

            R c000
        """.trimIndent()
        val parsed = Transcript.fromText(text)
        assertEquals(2, parsed.events.size)
        assertEquals(Direction.WRITE, parsed.events[0].direction)
    }

    @Test
    fun replayServesReadsAcrossMultipleCalls() {
        val replay = ReplayTransport(
            Transcript(listOf(TransportEvent(Direction.READ, byteArrayOf(1, 2, 3, 4, 5)))),
            strictWrites = false,
        )
        val buf = ByteArray(2)
        assertEquals(2, replay.read(buf, 0, 2, 100))
        assertContentEquals(byteArrayOf(1, 2), buf)
        assertEquals(2, replay.read(buf, 0, 2, 100))
        assertContentEquals(byteArrayOf(3, 4), buf)
        assertEquals(1, replay.read(buf, 0, 2, 100))
        assertEquals(5, buf[0])
    }

    @Test
    fun replayRaisesRecordedTimeout() {
        val replay = ReplayTransport(
            Transcript(listOf(TransportEvent(Direction.READ, ByteArray(0), timeout = true))),
            strictWrites = false,
        )
        assertFailsWith<TransportTimeoutException> { replay.read(ByteArray(4), 0, 4, 100) }
    }

    @Test
    fun strictReplayDetectsWriteMismatch() {
        val replay = ReplayTransport(
            Transcript(listOf(TransportEvent(Direction.WRITE, byteArrayOf(0x01, 0x02)))),
        )
        assertFailsWith<TransportException> { replay.write(byteArrayOf(0x01, 0x03)) }
    }

    @Test
    fun recordingCapturesWhatPassesThrough() {
        var now = 0L
        val replay = ReplayTransport(
            Transcript(
                listOf(
                    TransportEvent(Direction.WRITE, byteArrayOf(0x53)),
                    TransportEvent(Direction.READ, byteArrayOf(0x61, 0x62)),
                ),
            ),
        )
        val recording = RecordingTransport(replay) { now }
        recording.write(byteArrayOf(0x53))
        now = 7
        val buf = ByteArray(8)
        val n = recording.read(buf, 0, 8, 100)
        assertEquals(2, n)

        val events = recording.transcript().events
        assertEquals(2, events.size)
        assertEquals(Direction.WRITE, events[0].direction)
        assertContentEquals(byteArrayOf(0x61, 0x62), events[1].data)
        assertTrue(events[1].atMillis >= 0)
    }
}
