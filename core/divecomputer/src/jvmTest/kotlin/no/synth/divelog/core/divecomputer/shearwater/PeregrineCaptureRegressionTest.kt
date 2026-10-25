package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerKind
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.transport.ReplayTransport
import no.synth.divelog.core.divecomputer.transport.Transcript
import no.synth.divelog.core.model.DecoAlgorithm
import no.synth.divelog.core.model.DecoModel
import no.synth.divelog.core.model.DiveMode
import no.synth.divelog.core.model.units.ZERO_CELSIUS_MK
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Replays a real Peregrine download over Bluetooth LE (trimmed to the session setup and the
 * newest dive) and checks the dive against what the owner's logbook showed for it. This
 * locks in the BLE packet layer, the identifiers (the model one added by hand), the record-format log and the shutdown.
 */
class PeregrineCaptureRegressionTest {
    private val transcript = Transcript.fromText(
        requireNotNull(javaClass.getResourceAsStream("/peregrine-capture.transcript.txt")) { "missing transcript" }
            .bufferedReader().readText(),
    )

    @Test
    fun replaysTheDownloadAndParsesTheDive() {
        // Strict: every packet the protocol writes must match the recorded one, and all of
        // them must be written, the shutdown that ends the session included.
        var reported: DeviceInfo? = null
        val replay = ReplayTransport(transcript)
        val raw = DiveComputerKind.SHEARWATER.protocol(replay).download(
            knownFingerprint = null,
            listener = object : DownloadListener {
                override fun onDeviceInfo(info: DeviceInfo) { reported = info }
            },
            limit = 1,
        ).single()
        assertTrue(replay.finished, "the protocol stopped before the end of the capture")
        assertEquals(DeviceInfo(vendor = "Shearwater", model = "Peregrine", serial = "12345678", firmware = "V89 Classic"), reported)
        assertEquals(PredatorParser.PNF_FORMAT_ID, raw.formatId) // log base 0x80000000
        assertEquals("6adcabc3", raw.fingerprint)

        val dive = PredatorParser().parse(raw)
        assertEquals(6, dive.number)
        assertEquals(1_792_846_787, dive.startEpochSeconds) // 2026-10-24 12:59:47 on the device clock
        assertEquals(23 * 60 + 41, dive.durationSeconds)
        assertEquals(8_200, dive.maxDepthMm)
        assertEquals(5_386, dive.meanDepthMm)
        assertEquals(ZERO_CELSIUS_MK + 9_000, dive.waterTempMk)
        assertEquals(143, dive.samples.size)
        assertEquals(10, dive.samples[1].timeOffsetSeconds)
        assertEquals(DiveMode.OPEN_CIRCUIT, dive.diveMode)
        assertEquals(DecoModel(DecoAlgorithm.BUHLMANN, gfLowPercent = 40, gfHighPercent = 85), dive.decoModel)
        // A shallow no-deco dive on air: NDL 99 min, no stop, TTS 1 min, no ppO2 or CNS.
        dive.samples.forEach {
            assertEquals(99 * 60, it.ndlSeconds)
            assertEquals(0, it.stopDepthMm)
            assertEquals(60, it.ttsSeconds)
            assertEquals(null, it.ppO2Mbar)
            assertEquals(0, it.cnsPermille)
        }
    }

    @Test
    fun withoutAWriteSizeTheCaptureIsNotAByteStream() {
        // A link without a write size is Bluetooth Classic, which this capture is not.
        val asStream = ReplayTransport(transcript.copy(maxWriteSize = null))
        runCatching { DiveComputerKind.SHEARWATER.protocol(asStream).download(knownFingerprint = null, limit = 1) }
        assertFalse(asStream.finished)
    }
}
