package no.synth.divelog.core.logbook.io

import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.shearwater.PredatorParser
import no.synth.divelog.core.formats.ComputerEntry
import no.synth.divelog.core.formats.DiveEntry
import no.synth.divelog.core.formats.DiveLog
import no.synth.divelog.core.formats.GitLogFormat
import no.synth.divelog.core.formats.SubsurfaceXml
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.model.DecoAlgorithm
import no.synth.divelog.core.model.DecoModel
import no.synth.divelog.core.model.DiveMode
import no.synth.divelog.core.model.Sample
import kotlin.test.Test
import kotlin.test.assertEquals

/** Dive mode, deco model and time to surface through import, the database and export. */
class DiveSettingsIoTest {
    private val container = AppContainer(createDatabase())
    private val io = LogbookIo(container)
    private val start = 1_700_000_000L

    @Test
    fun importAndExportKeepEveryComputersSettings() {
        val gf = DecoModel(DecoAlgorithm.BUHLMANN, 35, 75)
        val dive = DiveEntry(
            startEpochSeconds = start,
            durationSeconds = 2_400,
            computers = listOf(
                ComputerEntry(
                    model = "Shearwater Petrel",
                    serial = "A1",
                    diveMode = DiveMode.CLOSED_CIRCUIT,
                    decoModel = gf,
                    samples = listOf(Sample(0, depthMm = 1_000, ttsSeconds = 60), Sample(60, depthMm = 20_000, ttsSeconds = 300)),
                ),
                ComputerEntry(model = "Suunto HelO2", serial = "B2", diveMode = DiveMode.GAUGE),
            ),
        )
        io.importMessage(SubsurfaceXml().write(DiveLog(listOf(dive))))

        val stored = container.dives.allDives().single()
        val records = container.dives.recordsForDive(stored.id).associateBy { r -> r.deviceId?.let { container.devices.get(it) }?.model }
        assertEquals(DiveMode.CLOSED_CIRCUIT, records["Petrel"]?.diveMode)
        assertEquals(gf, records["Petrel"]?.decoModel)
        assertEquals(DiveMode.GAUGE, records["HelO2"]?.diveMode) // an extra computer keeps its settings too

        val computers = SubsurfaceXml().read(io.exportAll(SubsurfaceXml())).dives.single().computers.associateBy { it.model }
        val petrel = computers.getValue("Shearwater Petrel")
        assertEquals(DiveMode.CLOSED_CIRCUIT, petrel.diveMode)
        assertEquals(gf, petrel.decoModel)
        assertEquals(listOf(60, 300), petrel.samples.map { it.ttsSeconds })
        assertEquals(DiveMode.GAUGE, computers.getValue("Suunto HelO2").diveMode)
    }

    @Test
    fun aStopThatEndsStaysEndedThroughTheSubsurfaceFormats() {
        // A Shearwater record log: a 3 m stop (2 min, TTS 5 min), then no stop (NDL 50, TTS 0).
        fun record(type: Int, vararg fields: Pair<Int, Int>) = ByteArray(32).also { r ->
            r[0] = type.toByte()
            for ((offset, value) in fields) r[offset] = value.toByte()
        }
        // Sample fields sit one byte after the type: depth +1, stop depth +3, TTS +5, deco byte +10.
        val log = record(0x10) + record(0x14, 16 to 9) +
            record(0x01, 2 to 60, 4 to 3, 6 to 5, 10 to 2) +
            record(0x01, 2 to 40, 10 to 50)
        val parsed = PredatorParser().parse(RawDive("a", log, PredatorParser.PNF_FORMAT_ID)).samples
        fun fields(samples: List<Sample>) = samples.map { listOf(it.stopDepthMm, it.stopTimeSeconds, it.ndlSeconds, it.ttsSeconds) }
        assertEquals(listOf(listOf(3_000, 120, 0, 300), listOf(0, 0, 3_000, 0)), fields(parsed))

        val entry = DiveLog(listOf(DiveEntry(startEpochSeconds = start, durationSeconds = 20, computers = listOf(ComputerEntry(samples = parsed)))))
        fun samplesOf(log: DiveLog) = log.dives.single().computers.single().samples
        assertEquals(fields(parsed), fields(samplesOf(SubsurfaceXml().let { it.read(it.write(entry)) })), "Subsurface XML")
        assertEquals(fields(parsed), fields(samplesOf(GitLogFormat().let { it.read(it.write(entry)) })), "git tree")
    }
}
