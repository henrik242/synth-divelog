package no.synth.divelog.core.logbook.io

import no.synth.divelog.core.db.DiveRepository
import no.synth.divelog.core.divecomputer.shearwater.PredatorParser
import no.synth.divelog.core.model.DiveComputerRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecordOriginTest {
    private fun origin(formatId: String, fingerprint: String = "abc") = RecordOrigin.describe(
        DiveComputerRecord(diveId = 1, startEpochSeconds = 0, durationSeconds = 60, rawFormatId = formatId, fingerprint = fingerprint),
    )

    @Test
    fun namesTheSource() {
        assertEquals("Downloaded from the computer", origin(PredatorParser.PNF_FORMAT_ID))
        assertEquals("Imported from Shearwater Cloud", origin(PredatorParser.PNF_FORMAT_ID, "shearwater-cloud:42"))
        assertEquals("Imported from Shearwater Cloud", origin("shearwater-cloud"))
        assertEquals("Imported from Subsurface cloud", origin("subsurface-cloud"))
        assertEquals("Imported from Subsurface XML", origin("subsurface-xml"))
        assertEquals("Imported from UDDF", origin("uddf"))
        assertEquals("Imported from MacDive XML", origin("macdive-xml"))
        assertEquals("Imported from a file", origin("import-extra"))
        assertEquals("Joined from consecutive logs", origin(DiveRepository.CONCATENATED_FORMAT_ID))
        assertNull(origin("something-else"))
    }
}
