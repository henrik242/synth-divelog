package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.RawDive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the old-Vyper layers that need no transport: the XOR checksum,
 * ring-buffer extraction from a hand-built memory image, and the depth/temperature
 * parser. The memory image is constructed from the layout in
 * docs/protocol/suunto-zoop.md, so these lock the parser to that documented format.
 */
class SuuntoVyperTest {
    @Test
    fun xorChecksumMatchesTheKnownAlgorithm() {
        // 05 ^ 00 ^ 24 ^ 01 = 0x20
        assertEquals(0x20.toByte(), SuuntoCrc.xor(byteArrayOf(0x05, 0x00, 0x24, 0x01)))
        val appended = SuuntoCrc.appended(byteArrayOf(0x05, 0x00, 0x71, 0x20))
        assertEquals(0x05 xor 0x00 xor 0x71 xor 0x20, appended.last().toInt() and 0xFF)
    }

    @Test
    fun extractsOneDiveFromTheRingAndWrapsCorrectly() {
        val image = SyntheticVyper.squareDiveImage()
        val dives = SuuntoVyperDump.extract(image.ring, image.pointer)
        assertEquals(1, dives.size)
        assertEquals(SyntheticVyper.RECORD.toList(), dives[0].data.toList())
    }

    @Test
    fun parsesDepthProfileFromDeltas() {
        val raw = RawDive(
            fingerprint = "test",
            data = SyntheticVyper.RECORD,
            formatId = SuuntoVyperDump.FORMAT_ID,
        )
        val dive = SuuntoVyperParser(sampleIntervalSeconds = 20).parse(raw)

        // Six delta samples plus the surface start sample.
        assertEquals(7, dive.samples.size)
        assertEquals(6 * 20, dive.durationSeconds)
        // Max depth 66 ft = 20.1 m.
        assertEquals(66 * 3048 / 10, dive.maxDepthMm)
        // End temperature 18 C -> milli-kelvin.
        assertEquals(18 * 1_000 + 273_150, dive.waterTempMk)
        // Reconstructed forward profile in feet 0,33,33,66,66,33,0, stored in mm.
        val expectedMm = listOf(0, 33, 33, 66, 66, 33, 0).map { it * 3048 / 10 }
        assertEquals(expectedMm, dive.samples.map { it.depthMm })
    }

    @Test
    fun emptyRingYieldsNoDives() {
        val ring = ByteArray(SuuntoVyperDump.RING_END - SuuntoVyperDump.RING_BEGIN)
        // Pointer at a byte holding the end-of-dataset marker, nothing before it.
        ring[0] = SuuntoVyperDump.END_OF_DATA.toByte()
        assertTrue(SuuntoVyperDump.extract(ring, SuuntoVyperDump.RING_BEGIN).isEmpty())
    }

    @Test
    fun modelNamesCoverTheKnownTypes() {
        assertEquals("Zoop", SuuntoVyperDump.modelName(0x16))
        assertEquals("Vyper", SuuntoVyperDump.modelName(0x0C))
        assertTrue(SuuntoVyperDump.modelName(0xFF).startsWith("Vyper-family"))
    }
}
