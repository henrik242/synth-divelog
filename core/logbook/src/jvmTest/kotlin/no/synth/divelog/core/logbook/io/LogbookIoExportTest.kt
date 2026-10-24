package no.synth.divelog.core.logbook.io

import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.formats.ComputerEntry
import no.synth.divelog.core.formats.DiveEntry
import no.synth.divelog.core.formats.DiveLog
import no.synth.divelog.core.formats.SubsurfaceXml
import no.synth.divelog.core.formats.UddfFormat
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.model.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LogbookIoExportTest {
    private val container = AppContainer(createDatabase())
    private val io = LogbookIo(container)
    private val start = 1_700_000_000L

    private fun dive(day: Int) = DiveEntry(
        number = day + 1,
        startEpochSeconds = start + day * 86_400,
        durationSeconds = 2_400,
        computers = listOf(
            ComputerEntry(
                model = "Shearwater Petrel",
                serial = "A1",
                samples = listOf(Sample(0, depthMm = 0), Sample(60, depthMm = 10_000)),
            ),
        ),
    )

    /** Imports three dives, numbered 1 to 3, and returns their ids by number. */
    private fun threeDives(): Map<Int, Long> {
        io.importMessage(SubsurfaceXml().write(DiveLog((0 until 3).map(::dive))))
        return container.dives.allDives().associate { requireNotNull(it.number) to it.id }
    }

    private fun numbers(file: ExportFile) = SubsurfaceXml().read(file.bytes.decodeToString()).dives.mapNotNull { it.number }.sorted()

    @Test
    fun aSelectionExportsOnlyThoseDives() {
        val ids = threeDives()
        val file = requireNotNull(io.export(SubsurfaceXml().id, setOf(ids.getValue(1), ids.getValue(3))))
        assertEquals("synth-divelog-2-dives.xml", file.name)
        assertEquals(listOf(1, 3), numbers(file))
    }

    @Test
    fun withoutASelectionTheWholeLogbookIsExported() {
        threeDives()
        val file = requireNotNull(io.export(SubsurfaceXml().id))
        assertEquals("synth-divelog.xml", file.name)
        assertEquals(listOf(1, 2, 3), numbers(file))
    }

    @Test
    fun idsWithNoDiveAreSkippedAndNotCounted() {
        val ids = threeDives()
        val file = requireNotNull(io.export(SubsurfaceXml().id, setOf(ids.getValue(2), 9_999)))
        assertEquals("synth-divelog-1-dive.xml", file.name)
        assertEquals(listOf(2), numbers(file))
    }

    @Test
    fun anEmptySelectionExportsNoDives() {
        threeDives()
        val file = requireNotNull(io.export(SubsurfaceXml().id, emptySet()))
        assertEquals("synth-divelog-0-dives.xml", file.name)
        assertEquals(emptyList(), numbers(file))
    }

    @Test
    fun anUnknownTargetExportsNothing() {
        threeDives()
        assertNull(io.export("no-such-format"))
    }

    @Test
    fun everyTargetWritesTheSelectionWithItsOwnExtensionAndType() {
        val ids = threeDives()
        val picked = setOf(ids.getValue(1), ids.getValue(2))
        val expected = mapOf(
            "subsurface-xml" to ("xml" to "application/xml"),
            "uddf" to ("uddf" to "application/xml"),
            "macdive-xml" to ("xml" to "application/xml"),
            "shearwater-cloud-db" to ("db" to "application/vnd.sqlite3"),
        )
        assertEquals(expected.keys, LogbookIo.exportTargets().map { it.id }.toSet())
        LogbookIo.exportTargets().forEach { target ->
            val file = requireNotNull(io.export(target.id, picked))
            val (ext, mime) = expected.getValue(target.id)
            assertEquals("synth-divelog-2-dives.$ext", file.name, target.id)
            assertEquals(mime, file.mimeType, target.id)
        }
    }

    @Test
    fun aUddfSelectionReadsBack() {
        val ids = threeDives()
        val file = requireNotNull(io.export(UddfFormat().id, setOf(ids.getValue(3))))
        val back = UddfFormat().read(file.bytes.decodeToString()).dives.single()
        assertEquals(start + 2 * 86_400, back.startEpochSeconds)
    }

    @Test
    fun aShearwaterCloudSelectionReadsBack() {
        val ids = threeDives()
        val file = requireNotNull(io.export("shearwater-cloud-db", setOf(ids.getValue(1), ids.getValue(3))))
        val read = ShearwaterCloudDives.read(readShearwaterCloudExport(file.bytes))
        assertEquals(0, read.unreadable)
        assertEquals(listOf(start, start + 2 * 86_400), read.dives.map { it.entry.startEpochSeconds }.sorted())
    }
}
