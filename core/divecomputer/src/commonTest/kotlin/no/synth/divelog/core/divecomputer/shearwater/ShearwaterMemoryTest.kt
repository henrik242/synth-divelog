// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DownloadCancelledException
import no.synth.divelog.core.divecomputer.transport.Direction
import no.synth.divelog.core.divecomputer.transport.ReplayTransport
import no.synth.divelog.core.divecomputer.transport.Slip
import no.synth.divelog.core.divecomputer.transport.Transcript
import no.synth.divelog.core.divecomputer.transport.TransportEvent
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ShearwaterMemoryTest {
    /** Build a READ event carrying one response frame (01 FF len 00 + payload). */
    private fun response(vararg payload: Int): TransportEvent {
        val body = ByteArray(payload.size) { payload[it].toByte() }
        val header = byteArrayOf(0x01, 0xFF.toByte(), ((body.size + 1) and 0xFF).toByte(), 0x00)
        return TransportEvent(Direction.READ, Slip.encode(header + body))
    }

    @Test
    fun readsAcrossBlocksAndStopsAtSize() {
        val transcript = Transcript(
            listOf(
                response(0x75, 0x10, 0x04), // init: maxlen = 4
                response(0x76, 0x00, 0x01, 0x02, 0x03, 0x04), // block 0
                response(0x76, 0x01, 0x05, 0x06, 0x07, 0x08), // block 1
                response(0x77, 0x00), // exit
            ),
        )
        val memory = ShearwaterMemory(ShearwaterLink(ReplayTransport(transcript, strictWrites = false)))

        val data = memory.read(baseAddress = 0xDD000000L, size = 8)

        assertContentEquals(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), data)
    }

    @Test
    fun honoursCancellation() {
        val transcript = Transcript(
            listOf(
                response(0x75, 0x10, 0x04),
                response(0x76, 0x00, 0x01, 0x02, 0x03, 0x04),
            ),
        )
        val memory = ShearwaterMemory(ShearwaterLink(ReplayTransport(transcript, strictWrites = false)))
        assertFailsWith<DownloadCancelledException> {
            memory.read(baseAddress = 0L, size = 64, cancel = CancellationSignal { true })
        }
    }

    @Test
    fun reportsProgress() {
        val transcript = Transcript(
            listOf(
                response(0x75, 0x10, 0x04),
                response(0x76, 0x00, 0x01, 0x02, 0x03, 0x04),
                response(0x76, 0x01, 0x05, 0x06, 0x07, 0x08),
                response(0x77, 0x00),
            ),
        )
        val memory = ShearwaterMemory(ShearwaterLink(ReplayTransport(transcript, strictWrites = false)))
        val progress = mutableListOf<Int>()
        memory.read(baseAddress = 0L, size = 8, onProgress = { read, _ -> progress.add(read) })
        assertEquals(listOf(4, 8), progress)
    }
}
