package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.transport.ReplayTransport
import no.synth.divelog.core.divecomputer.transport.Transcript
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.units.Units
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Replays a real Predator download captured from the owner's device and checks
 * the decoded dives against the values shown on the device's own log screens.
 * This locks in the whole pipeline: SLIP framing, the command exchange, ring
 * extraction (including the newest dive, which wraps the buffer), and parsing.
 */
class PredatorCaptureRegressionTest {
    private fun loadDives(): Map<Int, IncomingDive> {
        val text = javaClass.getResourceAsStream("/predator-capture.transcript.txt")!!
            .bufferedReader().readText()
        val transport = ReplayTransport(Transcript.fromText(text), strictWrites = false)
        val raw = ShearwaterPredatorProtocol(transport).download(knownFingerprint = null)
        val parser = PredatorParser()
        return raw.associate { val d = parser.parse(it); d.number!! to d }
    }

    private fun assertDive(
        dive: IncomingDive?,
        maxDepthMetres: Double,
        avgDepthMetres: Double,
        durationMinutes: Int,
    ) {
        assertNotNull(dive)
        assertEquals(durationMinutes * 60, dive.durationSeconds)
        assertTrue(
            abs(Units.depthMetres(dive.maxDepthMm!!) - maxDepthMetres) <= 0.6,
            "max depth ${Units.depthMetres(dive.maxDepthMm!!)} vs $maxDepthMetres",
        )
        assertTrue(
            abs(Units.depthMetres(dive.meanDepthMm!!) - avgDepthMetres) <= 1.1,
            "avg depth ${Units.depthMetres(dive.meanDepthMm!!)} vs $avgDepthMetres",
        )
    }

    @Test
    fun decodedDivesMatchTheDeviceLog() {
        val dives = loadDives()

        // Device log screens (dive number -> max m, avg m, minutes).
        assertDive(dives[872], 60.0, 23.0, 58) // newest; wraps the ring buffer
        assertDive(dives[871], 36.0, 25.0, 44)
        assertDive(dives[870], 34.0, 20.0, 63)
        assertDive(dives[869], 33.0, 20.0, 64)
        assertDive(dives[868], 41.0, 24.0, 66)
        assertDive(dives[867], 62.0, 25.0, 72)
        assertDive(dives[866], 33.0, 20.0, 89)
        assertDive(dives[865], 39.0, 22.0, 70)
        assertDive(dives[864], 56.0, 23.0, 76)
        assertDive(dives[863], 27.0, 17.0, 28)

        // Dive 871 start time: 2025-10-26 10:46 as shown on the device (S-10:46).
        assertEquals(1_761_475_582L, dives[871]!!.startEpochSeconds)
    }

    @Test
    fun newestDiveIsRecovered() {
        val dives = loadDives()
        assertTrue(872 in dives, "newest dive (872), which wraps the ring, must be present")
    }
}
