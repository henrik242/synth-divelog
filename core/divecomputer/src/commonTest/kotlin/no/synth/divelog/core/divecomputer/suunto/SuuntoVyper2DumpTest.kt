package no.synth.divelog.core.divecomputer.suunto

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the Vyper2 directory walk on a crafted ring: two linked dives, with both the
 * exact-budget path (valid begin pointer) and the whole-ring fallback (corrupt begin).
 */
class SuuntoVyper2DumpTest {
    private fun ring(mem: ByteArray): ByteArray =
        mem.copyOfRange(SuuntoVyper2Dump.RB_PROFILE_BEGIN, SuuntoVyper2Dump.RB_PROFILE_END)

    private fun header(mem: ByteArray): ByteArray =
        mem.copyOfRange(SuuntoVyper2Dump.HEADER_OFFSET, SuuntoVyper2Dump.HEADER_OFFSET + SuuntoVyper2Dump.HEADER_SIZE)

    @Test
    fun walksTwoDivesNewestFirst() {
        val mem = SyntheticVyper2.memory(
            listOf(SyntheticVyper2.helo2Record(maxDepthCm = 300), SyntheticVyper2.helo2Record(maxDepthCm = 600)),
        )
        val dives = SuuntoVyper2Dump.extract(ring(mem), header(mem))
        assertEquals(2, dives.size)
        // Newest first: the second (max 600) record comes back first.
        val parser = SuuntoVyper2Parser()
        assertEquals(6000, parser.parse(dives[0]).maxDepthMm)
        assertEquals(3000, parser.parse(dives[1]).maxDepthMm)
        // Distinct fingerprints for distinct dives.
        assertTrue(dives[0].fingerprint != dives[1].fingerprint)
    }

    @Test
    fun fallsBackToWholeRingWhenBeginIsCorrupt() {
        val mem = SyntheticVyper2.memory(
            listOf(SyntheticVyper2.helo2Record(maxDepthCm = 300), SyntheticVyper2.helo2Record(maxDepthCm = 600)),
            validBegin = false,
        )
        val dives = SuuntoVyper2Dump.extract(ring(mem), header(mem))
        // The walk still recovers both dives and stops at the unwritten gap.
        assertEquals(2, dives.size)
    }

    @Test
    fun returnsEmptyWhenHeaderPointersAreOutOfRange() {
        val mem = SyntheticVyper2.memory(listOf(SyntheticVyper2.helo2Record()))
        val bad = header(mem)
        bad[0] = 0x00; bad[1] = 0x00 // last = 0, below the ring
        assertEquals(0, SuuntoVyper2Dump.extract(ring(mem), bad).size)
    }

    @Test
    fun decodesSerialAsTwoDigitsPerByte() {
        assertEquals("94803072", SuuntoVyper2Dump.decodeSerial(byteArrayOf(0x5E, 0x50, 0x1E, 0x48)))
    }
}
