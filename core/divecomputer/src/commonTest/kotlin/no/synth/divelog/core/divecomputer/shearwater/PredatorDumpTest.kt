// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.divecomputer.shearwater

import kotlin.test.Test
import kotlin.test.assertEquals

class PredatorDumpTest {
    private val blockSize = PredatorDump.BLOCK_SIZE

    private fun dump(blockCount: Int): ByteArray =
        ByteArray(blockCount * blockSize) { 0xFF.toByte() } // start all-empty

    private fun writeOpening(memory: ByteArray, block: Int, number: Int, fingerprint: ByteArray) {
        val base = block * blockSize
        for (i in base until base + blockSize) memory[i] = 0 // clear so it is not an empty block
        memory[base] = 0xFF.toByte()
        memory[base + 1] = 0xFF.toByte()
        memory[base + 2] = ((number ushr 8) and 0xFF).toByte()
        memory[base + 3] = (number and 0xFF).toByte()
        fingerprint.copyInto(memory, base + 12)
    }

    private fun writeClosing(memory: ByteArray, block: Int) {
        val base = block * blockSize
        for (i in base until base + blockSize) memory[i] = 0
        memory[base] = 0xFF.toByte()
        memory[base + 1] = 0xFE.toByte()
    }

    @Test
    fun extractsDivesNewestFirstWithFingerprints() {
        val memory = dump(8)
        writeOpening(memory, block = 0, number = 5, fingerprint = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
        writeClosing(memory, block = 1)
        writeOpening(memory, block = 2, number = 9, fingerprint = byteArrayOf(0x11, 0x22, 0x33, 0x44))
        writeClosing(memory, block = 3)
        // blocks 4..7 remain empty (all 0xFF)

        val dives = PredatorDump.extract(memory)

        assertEquals(2, dives.size)
        assertEquals("11223344", dives[0].fingerprint) // number 9, newest
        assertEquals("aabbccdd", dives[1].fingerprint) // number 5
        assertEquals(2 * blockSize, dives[0].data.size)
        assertEquals(PredatorDump.FORMAT_ID, dives[0].formatId)
    }

    @Test
    fun ignoresEmptyRing() {
        assertEquals(0, PredatorDump.extract(dump(4)).size)
    }
}
