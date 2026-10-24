package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.RawDive
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The record form Shearwater Cloud stores dives in carries the same fields as the block
 * form. Rebuild the real Petrel dive in record form (opening and closing records are the
 * blocks' first 32 bytes behind a type byte, the closing one holding the duration in
 * seconds; each sample is a type byte and the sample) and it must parse to the same dive.
 */
class PnfRecordLayoutTest {
    private fun petrelBlocks(): ByteArray {
        val hex = requireNotNull(javaClass.getResourceAsStream("/petrel-dive-584.hex")) { "missing fixture" }
            .bufferedReader().readText().trim()
        val compressed = ByteArray(hex.length / 2) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() }
        val data = ShearwaterCompression.decompressLre(compressed).data
        ShearwaterCompression.decompressXor(data)
        return data
    }

    private fun toRecords(blocks: ByteArray): ByteArray {
        val closing = (BLOCK until blocks.size step BLOCK).first { blocks[it] == 0xFF.toByte() && blocks[it + 1] == 0xFE.toByte() }
        val out = ArrayList<ByteArray>()
        out += blocks.copyOfRange(0, RECORD).also { it[0] = 0x10 }
        for (offset in BLOCK until closing step RECORD) {
            out += byteArrayOf(0x01) + blocks.copyOfRange(offset, offset + RECORD - 1)
        }
        val minutes = ((blocks[closing + 6].toInt() and 0xFF) shl 8) or (blocks[closing + 7].toInt() and 0xFF)
        val seconds = minutes * 60
        out += blocks.copyOfRange(closing, closing + RECORD).also {
            it[0] = 0x20
            it[6] = (seconds shr 16).toByte()
            it[7] = (seconds shr 8).toByte()
            it[8] = seconds.toByte()
        }
        return out.fold(ByteArray(0)) { acc, r -> acc + r }
    }

    @Test
    fun recordFormParsesLikeTheBlockForm() {
        val blocks = petrelBlocks()
        val fromBlocks = PredatorParser().parse(RawDive("a", blocks, PredatorParser.PETREL_FORMAT_ID))
        val fromRecords = PredatorParser().parse(RawDive("b", toRecords(blocks), PredatorParser.PNF_FORMAT_ID))

        assertEquals(fromBlocks.number, fromRecords.number)
        assertEquals(fromBlocks.startEpochSeconds, fromRecords.startEpochSeconds)
        assertEquals(fromBlocks.durationSeconds, fromRecords.durationSeconds)
        assertEquals(fromBlocks.maxDepthMm, fromRecords.maxDepthMm)
        assertEquals(fromBlocks.meanDepthMm, fromRecords.meanDepthMm)
        assertEquals(fromBlocks.waterTempMk, fromRecords.waterTempMk)
        assertEquals(fromBlocks.samples, fromRecords.samples)
        assertEquals(fromBlocks.gases, fromRecords.gases)
    }

    @Test
    fun theOpeningRecordsSetTheSampleInterval() {
        val records = toRecords(petrelBlocks())
        fun parse(version: Int, intervalMs: Int) =
            PredatorParser().parse(RawDive("c", withIntervalRecords(records, version, intervalMs), PredatorParser.PNF_FORMAT_ID))

        // Log version 9 and later record the interval in milliseconds.
        val twoSeconds = parse(version = 9, intervalMs = 2_000)
        assertEquals(listOf(0, 2, 4), twoSeconds.samples.take(3).map { it.timeOffsetSeconds })
        // Before version 9 the field is not the interval, and an interval in part seconds
        // is not one samples can be placed on; both keep the 10 s default.
        assertEquals(listOf(0, 10, 20), parse(version = 8, intervalMs = 2_000).samples.take(3).map { it.timeOffsetSeconds })
        assertEquals(listOf(0, 10, 20), parse(version = 9, intervalMs = 2_500).samples.take(3).map { it.timeOffsetSeconds })
    }

    /** [records] with opening records 4 (log [version] at +16) and 5 ([intervalMs] at +23) after the first. */
    private fun withIntervalRecords(records: ByteArray, version: Int, intervalMs: Int): ByteArray {
        val opening4 = ByteArray(RECORD).also {
            it[0] = 0x14
            it[16] = version.toByte()
        }
        val opening5 = ByteArray(RECORD).also {
            it[0] = 0x15
            it[23] = (intervalMs shr 8).toByte()
            it[24] = intervalMs.toByte()
        }
        return records.copyOfRange(0, RECORD) + opening4 + opening5 + records.copyOfRange(RECORD, records.size)
    }

    private companion object {
        const val BLOCK = 128
        const val RECORD = 32
    }
}
