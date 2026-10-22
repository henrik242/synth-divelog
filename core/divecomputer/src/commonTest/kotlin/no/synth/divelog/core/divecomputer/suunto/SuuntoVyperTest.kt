package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.model.EventType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the old-Vyper layers that need no transport: the XOR checksum,
 * ring-buffer extraction from a synthetic memory image, and the parser.
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
    fun extractsDivesNewestFirst() {
        val older = SyntheticVyper.record(minute = 0)
        val newer = SyntheticVyper.record(minute = 59)
        val image = SyntheticVyper.image(listOf(older, newer))
        val dives = SuuntoVyperDump.extract(image.ring, image.pointer)
        assertEquals(listOf(newer.toList(), older.toList()), dives.map { it.data.toList() })
    }

    @Test
    fun extractsAcrossTheRingWrap() {
        val ringLen = SuuntoVyperDump.RING_END - SuuntoVyperDump.RING_BEGIN
        val records = listOf(SyntheticVyper.record(minute = 0), SyntheticVyper.record(minute = 59))
        val image = SyntheticVyper.image(records, startAt = ringLen - 20)
        val dives = SuuntoVyperDump.extract(image.ring, image.pointer)
        assertEquals(records.reversed().map { it.toList() }, dives.map { it.data.toList() })
    }

    @Test
    fun parsesTheRecord() {
        val raw = RawDive(fingerprint = "test", data = SyntheticVyper.record(), formatId = SuuntoVyperDump.FORMAT_ID)
        val dive = SuuntoVyperParser().parse(raw)

        assertEquals(1_721_039_400L, dive.startEpochSeconds) // 2024-07-15 10:30
        assertEquals(6 * SyntheticVyper.INTERVAL, dive.durationSeconds)
        assertEquals(66 * 3048 / 10, dive.maxDepthMm)
        assertEquals(18_000 + 273_150, dive.waterTempMk)
        assertEquals(20_000 + 273_150, dive.airTempMk)
        assertEquals(listOf(320), dive.gases.map { it.o2Permille })

        // Surface start, six samples, surface end.
        val expectedFt = listOf(0, 33, 33, 66, 66, 33, 0, 0)
        assertEquals(expectedFt.map { it * 3048 / 10 }, dive.samples.map { it.depthMm })
        assertEquals(listOf(0, 20, 40, 60, 80, 100, 120, 140), dive.samples.map { it.timeOffsetSeconds })
        assertEquals(mapOf(0 to 200_000), dive.samples.first().tankPressuresMbar)
        assertEquals(mapOf(0 to 50_000), dive.samples.last().tankPressuresMbar)

        // The bookmark shares the timestamp of the sample that follows it.
        assertEquals(listOf(EventType.BOOKMARK to 80), dive.events.map { it.type to it.timeOffsetSeconds })
    }

    @Test
    fun airWhenO2IsZero() {
        val raw = RawDive(fingerprint = "t", data = SyntheticVyper.record(o2 = 0), formatId = SuuntoVyperDump.FORMAT_ID)
        assertEquals(listOf(210), SuuntoVyperParser().parse(raw).gases.map { it.o2Permille })
    }

    @Test
    fun emptyRingYieldsNoDives() {
        val ring = ByteArray(SuuntoVyperDump.RING_END - SuuntoVyperDump.RING_BEGIN)
        ring[0] = SuuntoVyperDump.END_OF_DATA.toByte()
        assertTrue(SuuntoVyperDump.extract(ring, SuuntoVyperDump.RING_BEGIN).isEmpty())
    }

    @Test
    fun modelNamesFollowLibdivecomputer() {
        assertEquals("Vyper", SuuntoVyperDump.modelName(0x0A))
        assertEquals("Vytec", SuuntoVyperDump.modelName(0x0B))
        assertEquals("Zoop", SuuntoVyperDump.modelName(0x16))
        assertTrue(SuuntoVyperDump.modelName(0xFF).startsWith("Vyper-family"))
    }
}
