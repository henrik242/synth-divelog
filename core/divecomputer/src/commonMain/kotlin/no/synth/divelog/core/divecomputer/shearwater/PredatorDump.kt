// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.transport.toHex

/**
 * Finds individual dives inside a Predator memory dump. The profile area is a
 * ring of fixed-size blocks: a dive begins with an opening block (first two
 * bytes FF FF, but not an all-FF empty block) and ends with a closing block
 * (first two bytes FF FE). Each dive carries its own number and a fingerprint.
 *
 * Wrap-around ordering of a nearly-full ring is finalised against a real
 * capture; this handles the common forward-ordered case and sorts newest first.
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
        val found = mutableListOf<Found>()

        var diveStart = -1
        var block = 0
        while (block * BLOCK_SIZE < ringEnd) {
            val base = block * BLOCK_SIZE
            when {
                diveStart < 0 && isOpening(memory, base) -> diveStart = base
                diveStart >= 0 && isClosing(memory, base) -> {
                    val end = minOf(base + BLOCK_SIZE, memory.size)
                    found += capture(memory, diveStart, end)
                    diveStart = -1
                }
            }
            block++
        }

        return found
            .sortedByDescending { it.number }
            .map { RawDive(fingerprint = it.fingerprint, data = it.data, formatId = FORMAT_ID) }
    }

    private fun capture(memory: ByteArray, start: Int, end: Int): Found {
        val data = memory.copyOfRange(start, end)
        val number = ((memory[start + NUMBER_OFFSET].toInt() and 0xFF) shl 8) or
            (memory[start + NUMBER_OFFSET + 1].toInt() and 0xFF)
        val fingerprint = memory
            .copyOfRange(start + FINGERPRINT_OFFSET, start + FINGERPRINT_OFFSET + FINGERPRINT_LEN)
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
