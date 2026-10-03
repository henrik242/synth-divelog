package no.synth.divelog.core.divecomputer.shearwater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ShearwaterManifestTest {
    private fun record(header: Int, fingerprint: ByteArray, address: Long): ByteArray {
        val r = ByteArray(32)
        r[0] = ((header shr 8) and 0xFF).toByte()
        r[1] = (header and 0xFF).toByte()
        fingerprint.copyInto(r, 4)
        for (i in 0 until 4) r[20 + i] = ((address shr (8 * (3 - i))) and 0xFF).toByte()
        return r
    }

    @Test
    fun parsesValidRecordsAndStopsAtTerminator() {
        val page = record(0xA5C4, byteArrayOf(0x68, 0xfd.toByte(), 0xfb.toByte(), 0xfe.toByte()), 0x1000) +
            record(0x5A23, byteArrayOf(0, 0, 0, 0), 0x2000) + // deleted, skipped
            record(0xA5C4, byteArrayOf(0x67, 0x50, 0x3f, 0x37), 0x3000) +
            record(0xFFFF, byteArrayOf(0, 0, 0, 0), 0) + // terminator
            ByteArray(32) // trailing

        val (entries, terminated) = ShearwaterPetrelProtocol.parseManifestPage(page)
        assertTrue(terminated)
        assertEquals(2, entries.size)
        assertEquals("68fdfbfe", entries[0].fingerprint)
        assertEquals(0x1000L, entries[0].address)
        assertEquals("67503f37", entries[1].fingerprint)
        assertEquals(0x3000L, entries[1].address)
    }

    @Test
    fun fullPageWithoutTerminatorIsNotTerminated() {
        val page = (0 until 48).fold(ByteArray(0)) { acc, i ->
            acc + record(0xA5C4, byteArrayOf(i.toByte(), 0, 0, 0), (i * 0x100).toLong())
        }
        val (entries, terminated) = ShearwaterPetrelProtocol.parseManifestPage(page)
        assertEquals(48, entries.size)
        assertTrue(!terminated)
    }
}
