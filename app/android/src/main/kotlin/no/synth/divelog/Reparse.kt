package no.synth.divelog

import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.shearwater.PredatorDump
import no.synth.divelog.core.divecomputer.shearwater.PredatorParser
import no.synth.divelog.ui.AppContainer

/**
 * Re-reads every stored dive from its saved raw download and rebuilds the parsed
 * profile, so a parser improvement reaches already-imported dives without a
 * re-download. User-entered fields are preserved by the repository.
 */
object Reparse {
    private val predatorParser = PredatorParser()
    private val handledFormats = setOf(PredatorDump.FORMAT_ID, PredatorParser.PETREL_FORMAT_ID)

    /** Returns the number of records re-parsed. */
    fun all(container: AppContainer): Int {
        var count = 0
        for (dive in container.dives.allDives()) {
            for (record in container.dives.recordsForDive(dive.id)) {
                if (record.rawFormatId !in handledFormats) continue
                val raw = container.dives.rawData(record.id) ?: continue
                val incoming = runCatching {
                    predatorParser.parse(RawDive(record.fingerprint, raw, record.rawFormatId))
                }.getOrNull() ?: continue
                container.dives.reparseRecord(record.id, incoming)
                count++
            }
        }
        return count
    }
}
