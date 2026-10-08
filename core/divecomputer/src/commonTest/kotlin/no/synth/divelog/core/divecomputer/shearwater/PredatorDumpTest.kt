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
    fun extractsADiveThatWrapsTheRingBoundary() {
        // Newest dive opens near the end (block 6) and its closing wraps to block 1.
        val memory = dump(8)
        writeOpening(memory, block = 3, number = 800, fingerprint = byteArrayOf(0x11, 0x22, 0x33, 0x44))
        writeClosing(memory, block = 4)
        writeOpening(memory, block = 6, number = 900, fingerprint = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte()))
        writeClosing(memory, block = 1)

        val dives = PredatorDump.extract(memory)

        assertEquals(2, dives.size)
        // Wrapped dive is newest (number 900): blocks 6,7 + 0,1 = 4 blocks.
        assertEquals("aabbccdd", dives[0].fingerprint)
        assertEquals(4 * blockSize, dives[0].data.size)
        assertEquals("11223344", dives[1].fingerprint)
        assertEquals(2 * blockSize, dives[1].data.size)
    }

    @Test
    fun ignoresEmptyRing() {
        assertEquals(0, PredatorDump.extract(dump(4)).size)
    }

    @Test
    fun skipsAnOpeningThatNeverClosed() {
        // Dive 7 at block 2 was interrupted: the next block opens dive 8. Pairing it with the
        // next closing (or, at the end, wrapping to the first) would glue it onto dive 8.
        val memory = dump(8)
        writeOpening(memory, block = 0, number = 6, fingerprint = byteArrayOf(1, 1, 1, 1))
        writeClosing(memory, block = 1)
        writeOpening(memory, block = 2, number = 7, fingerprint = byteArrayOf(2, 2, 2, 2))
        writeOpening(memory, block = 3, number = 8, fingerprint = byteArrayOf(3, 3, 3, 3))
        writeClosing(memory, block = 4)
        writeOpening(memory, block = 6, number = 9, fingerprint = byteArrayOf(4, 4, 4, 4))
        // block 6 never closes and nothing follows: no wrap to the closing at block 1.

        val dives = PredatorDump.extract(memory)

        assertEquals(listOf("03030303", "01010101"), dives.map { it.fingerprint })
        assertEquals(2 * blockSize, dives[0].data.size)
    }

    @Test
    fun aWrappedDiveNeedsNoOpeningBeforeItsClosing() {
        // Block 7 opens, the ring wraps, and block 0 opens again before the closing at
        // block 1: block 7 is an interrupted dive, block 0 the real one.
        val memory = dump(8)
        writeOpening(memory, block = 7, number = 3, fingerprint = byteArrayOf(7, 7, 7, 7))
        writeOpening(memory, block = 0, number = 4, fingerprint = byteArrayOf(8, 8, 8, 8))
        writeClosing(memory, block = 1)

        val dives = PredatorDump.extract(memory)

        assertEquals(listOf("08080808"), dives.map { it.fingerprint })
    }
}
