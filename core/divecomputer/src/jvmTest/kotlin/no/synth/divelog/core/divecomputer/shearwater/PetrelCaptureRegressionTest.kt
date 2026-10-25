package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.transport.ReplayTransport
import no.synth.divelog.core.divecomputer.transport.Transcript
import no.synth.divelog.core.model.DecoAlgorithm
import no.synth.divelog.core.model.DecoModel
import no.synth.divelog.core.model.DiveMode
import no.synth.divelog.core.model.units.ZERO_CELSIUS_MK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Replays a real Petrel 1 download over classic Bluetooth (trimmed to the first manifest
 * page and the three newest dives) and checks the dives. This locks in the manifest walk
 * stopping at the known dive, the block-log reads, the shutdown and the closed-circuit ppO2.
 */
class PetrelCaptureRegressionTest {
    private val transcript = Transcript.fromText(
        requireNotNull(javaClass.getResourceAsStream("/petrel-capture.transcript.txt")) { "missing transcript" }
            .bufferedReader().readText(),
    )

    @Test
    fun replaysTheDownloadAndParsesTheDives() {
        var reported: DeviceInfo? = null
        val replay = ReplayTransport(transcript)
        val raws = ShearwaterPetrel1Protocol(replay).download(
            knownFingerprint = "6a4ff81f", // dive 879: only the three after it are read
            listener = object : DownloadListener {
                override fun onDeviceInfo(info: DeviceInfo) { reported = info }
            },
        )
        assertTrue(replay.finished, "the protocol stopped before the end of the capture")
        assertEquals(DeviceInfo(vendor = "Shearwater", model = "Petrel"), reported)
        assertEquals(listOf("6a6f5108", "6a6f3831", "6a64aa0b"), raws.map { it.fingerprint })
        raws.forEach { assertEquals(PredatorParser.PETREL_FORMAT_ID, it.formatId) }

        val (oc, ccModeOnOc, cc) = raws.map { PredatorParser().parse(it) }
        val gf = DecoModel(DecoAlgorithm.BUHLMANN, gfLowPercent = 70, gfHighPercent = 90)

        assertEquals(882, oc.number)
        assertEquals(1_785_680_136, oc.startEpochSeconds)
        assertEquals(26 * 60, oc.durationSeconds)
        assertEquals(19_000, oc.maxDepthMm)
        assertEquals(11_359, oc.meanDepthMm)
        assertEquals(ZERO_CELSIUS_MK + 10_000, oc.waterTempMk)
        assertEquals(157, oc.samples.size)
        assertEquals(10, oc.samples[1].timeOffsetSeconds)
        assertEquals(DiveMode.OPEN_CIRCUIT, oc.diveMode)
        assertEquals(gf, oc.decoModel)
        assertTrue(oc.samples.all { it.ppO2Mbar == null })

        // The header says closed circuit, but every sample has the open-circuit bit: the
        // ppO2 is that of the gas breathed (here about 50% O2, 0.54 bar at 0.9 m).
        assertEquals(881, ccModeOnOc.number)
        assertEquals(DiveMode.CLOSED_CIRCUIT, ccModeOnOc.diveMode)
        assertEquals(205, ccModeOnOc.samples.size)
        assertEquals(540 to 900, ccModeOnOc.samples.first().let { it.ppO2Mbar to it.depthMm })
        assertTrue(ccModeOnOc.samples.all { it.ppO2Mbar != null })

        // Fixed setpoint 0.70 with no sensors calibrated (the samples flag the ppO2 as
        // external), then a bailout to open circuit from the 26th sample on.
        assertEquals(880, cc.number)
        assertEquals(1_784_982_027, cc.startEpochSeconds)
        assertEquals(32 * 60, cc.durationSeconds)
        assertEquals(5_000, cc.maxDepthMm)
        assertEquals(DiveMode.CLOSED_CIRCUIT, cc.diveMode)
        assertEquals(gf, cc.decoModel)
        assertEquals(List(25) { 700 }, cc.samples.take(25).map { it.ppO2Mbar })
        assertEquals(610 to 2_500, cc.samples[25].let { it.ppO2Mbar to it.depthMm })
        assertTrue(cc.samples.drop(25).all { it.ppO2Mbar != null })
    }
}
