package no.synth.divelog.core.db

import no.synth.divelog.core.model.DecoAlgorithm
import no.synth.divelog.core.model.DecoModel
import no.synth.divelog.core.model.DiveMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A record keeps the dive mode and deco model it was logged with, and samples their time to surface. */
class DiveSettingsTest {
    private val db = testDatabase()
    private val dives = DiveRepository(db)

    @Test
    fun settingsAndTimeToSurfaceAreStoredAndReparsed() {
        val gf = DecoModel(DecoAlgorithm.BUHLMANN, gfLowPercent = 30, gfHighPercent = 70)
        val created = dives.import(
            incoming(samples = listOf(sampleAt(0, 0).copy(ttsSeconds = 60), sampleAt(10, 5_000)))
                .copy(diveMode = DiveMode.OPEN_CIRCUIT, decoModel = gf),
        ) { false } as ImportResult.CreatedDive

        val record = dives.recordsForDive(created.diveId).single()
        assertEquals(DiveMode.OPEN_CIRCUIT, record.diveMode)
        assertEquals(gf, record.decoModel)
        assertEquals(listOf(60, null), dives.samplesForRecord(created.recordId).map { it.ttsSeconds })

        // A re-parse replaces them, here with a VPM-B dive on a rebreather.
        val vpm = DecoModel(DecoAlgorithm.VPM, conservatism = 2)
        dives.reparseRecord(
            created.recordId,
            incoming(samples = listOf(sampleAt(0, 0).copy(ttsSeconds = 0))).copy(diveMode = DiveMode.CLOSED_CIRCUIT, decoModel = vpm),
        )
        val reparsed = dives.recordsForDive(created.diveId).single()
        assertEquals(DiveMode.CLOSED_CIRCUIT, reparsed.diveMode)
        assertEquals(vpm, reparsed.decoModel)
        assertEquals(listOf(0), dives.samplesForRecord(created.recordId).map { it.ttsSeconds })

        // A parser that gives no settings clears them.
        dives.reparseRecord(created.recordId, incoming())
        val cleared = dives.recordsForDive(created.diveId).single()
        assertNull(cleared.diveMode)
        assertNull(cleared.decoModel)
    }

    @Test
    fun aRecordWithoutSettingsHasNone() {
        val created = dives.import(incoming()) { false } as ImportResult.CreatedDive
        val record = dives.recordsForDive(created.diveId).single()
        assertNull(record.diveMode)
        assertNull(record.decoModel)
    }
}
