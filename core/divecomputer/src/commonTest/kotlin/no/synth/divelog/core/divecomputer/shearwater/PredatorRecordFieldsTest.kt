package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.model.DecoAlgorithm
import no.synth.divelog.core.model.DecoModel
import no.synth.divelog.core.model.DiveMode
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.units.ZERO_CELSIUS_MK
import kotlin.test.Test
import kotlin.test.assertEquals

/** The record-format fields beyond depth and time: CNS, air integration, temperature, freedive. */
class PredatorRecordFieldsTest {
    /** A 32-byte record of [type] with [fields] set at their offsets from the record start. */
    private fun record(type: Int, vararg fields: Pair<Int, Int>): ByteArray =
        ByteArray(32).also { r ->
            r[0] = type.toByte()
            for ((offset, value) in fields) r[offset] = value.toByte()
        }

    private fun be16(offset: Int, value: Int) = arrayOf(offset to (value shr 8), offset + 1 to value)

    /** Sample fields sit one byte after the type byte. */
    private fun sample(depthTenths: Int, vararg fields: Pair<Int, Int>) =
        record(0x01, *be16(1, depthTenths), *fields.map { (o, v) -> o + 1 to v }.toTypedArray())

    private fun log(version: Int, vararg body: ByteArray, aiMode: Int = 0): ByteArray =
        record(0x10) + record(0x14, 16 to version, 28 to aiMode) + body.fold(ByteArray(0)) { a, b -> a + b }

    private fun parse(data: ByteArray, formatId: String = PredatorParser.PNF_FORMAT_ID): IncomingDive =
        PredatorParser().parse(RawDive("a", data, formatId))

    @Test
    fun cnsIsAPercentByte() {
        val dive = parse(log(9, sample(100, 22 to 12), sample(100, 22 to 13)))
        assertEquals(listOf(120, 130), dive.samples.map { it.cnsPermille })
    }

    @Test
    fun transmitterPressuresAreTwoPsiStepsFromLogVersion7() {
        // 1500 steps (3000 psi) on transmitter 1 under a battery nibble; transmitter 2 off.
        val reading = arrayOf(*be16(27, 0x1000 or 1500), *be16(19, 0xFFFF))
        val dive = parse(log(7, sample(100, *reading)))
        assertEquals(mapOf(0 to 206_843), dive.samples.single().tankPressuresMbar) // 3000 psi
        // Before version 7 the same bytes are not pressures.
        assertEquals(emptyMap(), parse(log(6, sample(100, *reading))).samples.single().tankPressuresMbar)
    }

    @Test
    fun extendedRecordsAddTransmittersAndTanksAreNumberedFromZero() {
        // Transmitter 1 off, transmitter 3 (extended record) at 1000 steps: it becomes tank 0.
        val dive = parse(log(13, sample(100, *be16(27, 0xFFFF), *be16(19, 0xFFFF)), record(0xE1, *be16(1, 1000))))
        assertEquals(mapOf(0 to 137_895), dive.samples.single().tankPressuresMbar) // 2000 psi
    }

    @Test
    fun anHpCcrPairComesFromTheTransmitterFields() {
        val dive = parse(log(13, sample(100, *be16(27, 1000), *be16(19, 500)), aiMode = 4))
        assertEquals(mapOf(0 to 137_895, 1 to 68_948), dive.samples.single().tankPressuresMbar)
    }

    @Test
    fun aNegativeTemperatureByteIsCorrectedTowardsZero() {
        // -100 is a reading just below the offset: +102 is 2, capped at 0 C. -105 is -3 C.
        val dive = parse(log(9, sample(100, 13 to -100), sample(100, 13 to -105)))
        assertEquals(listOf(ZERO_CELSIUS_MK, ZERO_CELSIUS_MK - 3_000), dive.samples.map { it.temperatureMk })
    }

    @Test
    fun freediveRecordsHoldFourSamplesOfAbsolutePressure() {
        // Surface 1000 mbar (opening 1), sea water 1025 kg/m3 (opening 3).
        fun fd(mbar: Int, tenthsC: Int) = ByteArray(8).also {
            it[1] = (mbar shr 8).toByte(); it[2] = mbar.toByte()
            it[3] = (tenthsC shr 8).toByte(); it[4] = tenthsC.toByte()
        }
        val packed = (fd(1500, 95) + fd(2005, 90) + fd(1200, 92) + ByteArray(8)).also { it[0] = 0x02 }
        val data = record(0x10) + record(0x11, *be16(16, 1000)) + record(0x13, *be16(3, 1025)) + packed
        val dive = parse(data)
        assertEquals(listOf(0, 10, 20), dive.samples.map { it.timeOffsetSeconds })
        assertEquals(listOf(4_974, 9_998, 1_990), dive.samples.map { it.depthMm }) // (mbar - 1000) * 100 / (1025 * g)
        assertEquals(ZERO_CELSIUS_MK + 9_500, dive.samples[0].temperatureMk)
        assertEquals(DiveMode.FREEDIVE, dive.diveMode)
    }

    @Test
    fun theDataNotTheFormatIdTellsBlocksFromRecords() {
        // A record log stored under the Petrel block id still parses as records.
        val records = log(9, sample(100), sample(200))
        assertEquals(20_000, parse(records, PredatorParser.PETREL_FORMAT_ID).maxDepthMm)
    }

    @Test
    fun theHeaderGivesTheDiveModeAndDecoModel() {
        // Opening 0: GF 30/70; opening 2: deco model 0 (GF); opening 4: log version 8, mode 6 (OC rec).
        val data = record(0x10, 4 to 30, 5 to 70) + record(0x12, 18 to 0) + record(0x14, 1 to 6, 16 to 8) + sample(100)
        val dive = parse(data)
        assertEquals(DiveMode.OPEN_CIRCUIT, dive.diveMode)
        assertEquals(DecoModel(DecoAlgorithm.BUHLMANN, gfLowPercent = 30, gfHighPercent = 70), dive.decoModel)

        // Header mode 0 (CC) over open-circuit samples: the header wins.
        val vpm = record(0x10) + record(0x12, 18 to 1, 19 to 3) + record(0x14, 1 to 0, 16 to 8) + sample(100, 11 to 0x10)
        assertEquals(DiveMode.CLOSED_CIRCUIT, parse(vpm).diveMode)
        assertEquals(DecoModel(DecoAlgorithm.VPM, conservatism = 3), parse(vpm).decoModel)
    }

    @Test
    fun everyDecoModelByte() {
        fun deco(model: Int, gfLow: Int = 30, gfHigh: Int = 70) =
            parse(record(0x10, 4 to gfLow, 5 to gfHigh) + record(0x12, 18 to model, 19 to 2) + record(0x14, 16 to 8) + sample(100)).decoModel
        assertEquals(DecoModel(DecoAlgorithm.BUHLMANN, 30, 70), deco(0))
        assertEquals(DecoModel(DecoAlgorithm.VPM, conservatism = 2), deco(1))
        assertEquals(DecoModel(DecoAlgorithm.VPM, conservatism = 2), deco(2)) // VPM-B/GFS
        assertEquals(DecoModel(DecoAlgorithm.DCIEM), deco(3))
        assertEquals(null, deco(9))
        // Gradient factors out of 1..150 are garbage, not settings.
        assertEquals(DecoModel(DecoAlgorithm.BUHLMANN), deco(0, gfLow = 0, gfHigh = 151))
    }

    @Test
    fun anUnknownHeaderModeFallsBackToTheSamples() {
        val data = record(0x10) + record(0x14, 1 to 9, 16 to 8) + sample(100, 11 to 0x10)
        assertEquals(DiveMode.OPEN_CIRCUIT, parse(data).diveMode)
    }

    @Test
    fun anOlderLogTakesItsDiveModeFromTheSamples() {
        // Log version 7: no mode in the header. Status 0x08 (no OC bit, SC bit) is semi-closed.
        assertEquals(DiveMode.SEMI_CLOSED, parse(log(7, sample(100, 11 to 0x08))).diveMode)
        assertEquals(DiveMode.CLOSED_CIRCUIT, parse(log(7, sample(100, 11 to 0x00))).diveMode)
        assertEquals(DiveMode.OPEN_CIRCUIT, parse(log(7, sample(100, 11 to 0x10))).diveMode)
    }

    @Test
    fun timeToSurfaceIsTheWordAfterTheStopDepthInMinutes() {
        val dive = parse(log(9, sample(100, *be16(4, 12)), sample(100)))
        // 0 stays 0: a file format would carry the previous value over a null.
        assertEquals(listOf(12 * 60, 0), dive.samples.map { it.ttsSeconds })
    }
}
