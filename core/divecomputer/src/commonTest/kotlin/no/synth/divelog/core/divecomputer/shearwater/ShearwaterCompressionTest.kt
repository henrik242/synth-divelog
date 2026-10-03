package no.synth.divelog.core.divecomputer.shearwater

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertTrue

class ShearwaterCompressionTest {
    @Test
    fun lreDecodesLiteralsRunsAndEnd() {
        // Codes: literal 0x41, run of 3 zeros, literal 0x42, end(0).
        // Packed MSB-first as a 9-bit stream -> these bytes.
        val input = byteArrayOf(0xA0.toByte(), 0x80.toByte(), 0xE8.toByte(), 0x40, 0x00)
        val result = ShearwaterCompression.decompressLre(input)
        assertTrue(result.complete)
        assertContentEquals(byteArrayOf(0x41, 0, 0, 0, 0x42), result.data)
    }

    @Test
    fun xorUndoesBlockChaining() {
        // Build data where block1 = block0 xor pattern, so undo recovers pattern copies.
        val block0 = ByteArray(32) { it.toByte() }
        val plainBlock1 = ByteArray(32) { (it + 100).toByte() }
        val encodedBlock1 = ByteArray(32) { (plainBlock1[it].toInt() xor block0[it].toInt()).toByte() }
        val data = block0 + encodedBlock1
        ShearwaterCompression.decompressXor(data)
        assertContentEquals(block0, data.copyOfRange(0, 32))
        assertContentEquals(plainBlock1, data.copyOfRange(32, 64))
    }
}
