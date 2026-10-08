package no.synth.divelog.ui.io

import no.synth.divelog.core.db.DriverFactory
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.formats.ComputerEntry
import no.synth.divelog.core.formats.DiveEntry
import no.synth.divelog.core.formats.DiveLog
import no.synth.divelog.core.formats.SubsurfaceXml
import no.synth.divelog.core.formats.TankEntry
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.ui.AppContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LogbookIoGasTest {
    private val container = AppContainer(DriverFactory().createDatabase())
    private val io = LogbookIo(container)

    @Test
    fun aLogbookCopyOfADownloadedDiveFillsInItsTankAndMetadata() {
        // Downloaded: the computer knows the gas, not the tank.
        val petrel = container.devices.getOrCreate(
            Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "serial-spp:shearwater-petrel"),
        )
        container.dives.import(
            IncomingDive(
                deviceId = petrel,
                startEpochSeconds = 1_700_000_000,
                utcOffsetSeconds = 0,
                durationSeconds = 2_400,
                maxDepthMm = 20_000,
                rawData = byteArrayOf(1),
                rawFormatId = "test",
                fingerprint = "petrel-1",
                gases = listOf(GasMix(o2Permille = 320, hePermille = 0)),
            ),
        ) { false }

        // The same dive in a logbook, with the tank details and a buddy.
        val log = DiveLog(
            listOf(
                DiveEntry(
                    startEpochSeconds = 1_700_000_000,
                    durationSeconds = 2_400,
                    buddies = listOf("Alex"),
                    tanks = listOf(
                        TankEntry(index = 0, volumeMl = 12_000, startPressureMbar = 210_000, endPressureMbar = 60_000, o2Permille = 320, hePermille = 0),
                    ),
                    computers = listOf(ComputerEntry(model = "Shearwater Petrel", maxDepthMm = 20_000)),
                ),
            ),
        )
        val message = io.importMessage(SubsurfaceXml().write(log))

        assertTrue(message.contains("skipped 1"), message)
        val diveId = container.dives.allDives().single().id
        val tank = container.gases.tanksForDive(diveId).single()
        assertEquals(12_000, tank.volumeMl)
        assertEquals(210_000, tank.startPressureMbar)
        assertEquals(60_000, tank.endPressureMbar)
        assertEquals(listOf("Alex"), container.buddies.buddiesForDive(diveId).map { it.name })
    }

    @Test
    fun aTankWithoutAGasStaysWithoutOneAndPairsBySize() {
        fun log(vararg tanks: TankEntry) = SubsurfaceXml().write(
            DiveLog(listOf(DiveEntry(startEpochSeconds = 1_700_000_000, durationSeconds = 2_400, tanks = tanks.toList()))),
        )
        // First a logbook that names no gas: the tank keeps no gas.
        io.importMessage(log(TankEntry(index = 0, volumeMl = 24_000, startPressureMbar = 200_000)))
        val diveId = container.dives.allDives().single().id
        assertEquals(null, container.gases.tanksForDive(diveId).single().gasMixId)

        // Then one that knows it: the same-size tank gets the gas instead of a second tank.
        io.importMessage(log(TankEntry(index = 0, volumeMl = 24_000, o2Permille = 320, hePermille = 0)))
        val tank = container.gases.tanksForDive(diveId).single()
        assertEquals(320, tank.gasMixId?.let { container.gases.gasMix(it) }?.o2Permille)
        assertEquals(200_000, tank.startPressureMbar)
    }

    @Test
    fun aSecondComputersOwnStartAndDurationSurviveImportAndExport() {
        val dive = DiveEntry(
            startEpochSeconds = 1_700_000_000,
            durationSeconds = 2_400,
            computers = listOf(
                ComputerEntry(model = "Shearwater Petrel", serial = "A1", maxDepthMm = 20_000),
                ComputerEntry(model = "Suunto HelO2", serial = "B2", maxDepthMm = 19_800, startEpochSeconds = 1_700_000_030, durationSeconds = 2_300),
            ),
        )
        io.importMessage(SubsurfaceXml().write(DiveLog(listOf(dive))))

        val exported = SubsurfaceXml().read(io.exportAll(SubsurfaceXml())).dives.single()
        val second = exported.computers.single { it.serial == "B2" }
        assertEquals(1_700_000_030, second.startEpochSeconds)
        assertEquals(2_300, second.durationSeconds)
        assertEquals(null, exported.computers.single { it.serial == "A1" }.startEpochSeconds)
    }
}
