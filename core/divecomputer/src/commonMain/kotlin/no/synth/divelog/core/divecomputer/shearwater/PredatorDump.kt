package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.transport.toHex

/**
 * Finds individual dives inside a Predator memory dump. The profile area is a
 * ring of fixed-size blocks: a dive begins with an opening block (first two
 * bytes FF FF, but not an all-FF empty block) and ends with a closing block
 * (first two bytes FF FE). Each dive carries its own number and a fingerprint.
 *
 * The ring is circular: the newest dive can begin near the end and have its
 * closing block wrap back to the start. Each opening is paired with the next
 * closing in circular order (wrapping once past the end), and a wrapped dive's
 * bytes are stitched across the boundary. Orphan closings, left when an old
 * dive's opening has been overwritten, are ignored. Dives are returned newest
 * first by dive number.
 */
object PredatorDump {
    const val BLOCK_SIZE = 0x80
    const val RING_END = 0x1F600
    const val MODEL_OFFSET = 0x2000D
    const val FORMAT_ID = "shearwater-predator-log"

    private const val OPEN_MARKER_1 = 0xFF
    private const val OPEN_MARKER_2 = 0xFF
    private const val CLOSE_MARKER_1 = 0xFF
    private const val CLOSE_MARKER_2 = 0xFE
    private const val NUMBER_OFFSET = 2
    private const val FINGERPRINT_OFFSET = 12
    private const val FINGERPRINT_LEN = 4

    private class Found(val number: Int, val fingerprint: String, val data: ByteArray)

    fun extract(memory: ByteArray): List<RawDive> {
        val ringEnd = minOf(RING_END, memory.size)
        val openings = mutableListOf<Int>()
        val closings = mutableListOf<Int>()
        var base = 0
        while (base + 2 <= ringEnd) {
            if (isOpening(memory, base)) openings.add(base)
            if (isClosing(memory, base)) closings.add(base)
            base += BLOCK_SIZE
        }
        if (closings.isEmpty()) return emptyList()
        val sortedClosings = closings.sorted()

        val found = openings.map { opening ->
            val closing = nextClosing(opening, sortedClosings)
            val data = if (closing > opening) {
                memory.copyOfRange(opening, minOf(closing + BLOCK_SIZE, memory.size))
            } else {
                // Wrapped: opening near the end, closing back at the start.
                memory.copyOfRange(opening, ringEnd) +
                    memory.copyOfRange(0, minOf(closing + BLOCK_SIZE, memory.size))
            }
            capture(data)
        }

        return found
            .sortedByDescending { it.number }
            .map { RawDive(fingerprint = it.fingerprint, data = it.data, formatId = FORMAT_ID) }
    }

    /** First closing after [opening]; if none, wrap to the earliest closing. */
    private fun nextClosing(opening: Int, sortedClosings: List<Int>): Int =
        sortedClosings.firstOrNull { it > opening } ?: sortedClosings.first()

    /** Read number and fingerprint from a reassembled dive (opening block at index 0). */
    private fun capture(data: ByteArray): Found {
        val number = ((data[NUMBER_OFFSET].toInt() and 0xFF) shl 8) or
            (data[NUMBER_OFFSET + 1].toInt() and 0xFF)
        val fingerprint = data
            .copyOfRange(FINGERPRINT_OFFSET, FINGERPRINT_OFFSET + FINGERPRINT_LEN)
            .toHex()
        return Found(number, fingerprint, data)
    }

    private fun isOpening(memory: ByteArray, base: Int): Boolean =
        byteAt(memory, base) == OPEN_MARKER_1 &&
            byteAt(memory, base + 1) == OPEN_MARKER_2 &&
            !isEmptyBlock(memory, base)

    private fun isClosing(memory: ByteArray, base: Int): Boolean =
        byteAt(memory, base) == CLOSE_MARKER_1 && byteAt(memory, base + 1) == CLOSE_MARKER_2

    private fun isEmptyBlock(memory: ByteArray, base: Int): Boolean {
        val end = minOf(base + BLOCK_SIZE, memory.size)
        for (i in base until end) if ((memory[i].toInt() and 0xFF) != 0xFF) return false
        return true
    }

    private fun byteAt(memory: ByteArray, index: Int): Int =
        if (index < memory.size) memory[index].toInt() and 0xFF else -1
}
