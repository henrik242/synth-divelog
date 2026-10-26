package no.synth.divelog.core.logbook.io

import no.synth.divelog.core.db.DiveRepository
import no.synth.divelog.core.divecomputer.DiveComputerKind
import no.synth.divelog.core.model.DiveComputerRecord

/** How a computer record came into the logbook, told from its format id and fingerprint. */
object RecordOrigin {
    /** "Downloaded from the computer", "Imported from Subsurface XML" and so on; null when it cannot tell. */
    fun describe(record: DiveComputerRecord): String? = when {
        record.rawFormatId == DiveRepository.CONCATENATED_FORMAT_ID -> "Joined from consecutive logs"
        record.rawFormatId == LogbookIo.SHEARWATER_CLOUD_IMPORT ||
            record.fingerprint.startsWith(LogbookIo.SHEARWATER_CLOUD_FINGERPRINT) -> "Imported from Shearwater Cloud"
        record.rawFormatId == LogbookIo.SUBSURFACE_CLOUD_IMPORT -> "Imported from Subsurface cloud"
        // Extra computers of a file import before they carried the format.
        record.rawFormatId == LEGACY_EXTRA -> "Imported from a file"
        DiveComputerKind.parserFor(record.rawFormatId) != null -> "Downloaded from the computer"
        else -> LogbookIo.formats().firstOrNull { it.id == record.rawFormatId }?.let { "Imported from ${it.displayName}" }
    }

    private const val LEGACY_EXTRA = "import-extra"
}
