package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.RawDive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Decodes one real dive served by the owner's Petrel 1 and checks the result,
 * locking in the whole per-dive pipeline: run-length + XOR decompression and the
 * shared log parser. The fixture is the raw compressed bytes as they came off the
 * wire (one dive's accumulated transfer blocks, hex-encoded).
 *
 * Regression guard: a Petrel's decompressed dive carries a trailing block after
 * the closing block, so a parser that assumed the closing block was the last block
 * read the close marker as a 0xFFFE depth (6553.4 m) and padding as the duration.
 */
class PetrelDiveRegressionTest {
    private fun decodeFixture(resource: String = "/petrel-dive-584.hex"): ByteArray {
        val hex = javaClass.getResourceAsStream(resource)!!
            .bufferedReader().readText().trim()
        val compressed = ByteArray(hex.length / 2) { i ->
            hex.substring(i * 2, i * 2 + 2).toInt(16).toByte()
        }
        val lre = ShearwaterCompression.decompressLre(compressed)
        assertTrue(lre.complete, "compressed stream should self-terminate")
        val data = lre.data
        ShearwaterCompression.decompressXor(data)
        return data
    }

    private fun parseFixture() = PredatorParser().parse(
        RawDive(fingerprint = "petrel-584", data = decodeFixture(), formatId = PredatorParser.PETREL_FORMAT_ID),
    )

    @Test
    fun decodesRealPetrelDive() {
        val dive = parseFixture()

        // A nitrox-30 dive to ~5.2 m at 6 C (32-byte sample records).
        assertEquals(584, dive.number)
        assertEquals(1_441_468_156L, dive.startEpochSeconds)
        assertEquals(31 * 60, dive.durationSeconds)
        assertEquals(5_200, dive.maxDepthMm)
        assertEquals(2_746, dive.meanDepthMm)
        assertEquals(279_150, dive.waterTempMk) // 6 C
        assertEquals(208, dive.samples.size)
        assertEquals("petrel-584", dive.fingerprint)
    }

    @Test
    fun maxDepthIsNotTheCloseMarker() {
        // The old bug surfaced as a 0xFFFE depth = 6553.4 m on every dive.
        val dive = parseFixture()
        assertTrue(dive.maxDepthMm!! < 100_000, "max depth ${dive.maxDepthMm} looks like the close marker")
        assertTrue(dive.durationSeconds > 0, "duration should be read from the real closing block")
    }

    @Test
    fun imperialDiveTemperatureIsFahrenheit() {
        // Dive #2 was logged in imperial units, so its sample temperature is in
        // Fahrenheit; misreading it as Celsius gave implausible values.
        val dive = PredatorParser().parse(
            RawDive(
                fingerprint = "petrel-2",
                data = decodeFixture("/petrel-dive-2-imperial.hex"),
                formatId = PredatorParser.PETREL_FORMAT_ID,
            ),
        )
        assertEquals(2, dive.number)
        assertEquals(294_261, dive.waterTempMk) // ~21 C, from ~70 F
        assertTrue(
            dive.waterTempMk!! in 283_150..303_150,
            "water temp ${dive.waterTempMk} mK should be a sane 10-30 C",
        )
    }

    @Test
    fun petrelUses32ByteStrideSoTheProfileIsNotASawtooth() {
        // Reading a Petrel dive at the Predator's 16-byte stride interleaves the
        // real sample with padding, doubling the count and inventing deep spikes.
        val dive = parseFixture()
        val depths = dive.samples.map { it.depthMm ?: 0 }
        // No neighbouring pair should jump by more than ~5 m (50 dm) in one 10 s step.
        val maxJump = depths.zipWithNext().maxOf { (a, b) -> kotlin.math.abs(a - b) }
        assertTrue(maxJump < 6_000, "implausible depth jump $maxJump mm between samples")
    }
}
