package no.synth.divelog.core.formats

import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.Sample
import no.synth.divelog.core.model.GasSwitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GitLogFormatTest {
    private val format = GitLogFormat()

    private fun sampleLog() = DiveLog(
        listOf(
            DiveEntry(
                number = 872,
                startEpochSeconds = 1_761_475_582, // 2025-10-26 10:46:22 UTC
                durationSeconds = 44 * 60,
                maxDepthMm = 35_900,
                meanDepthMm = 25_400,
                waterTempMk = 279_150,
                notes = "Wall dive\nsecond line",
                rating = 4,
                visibilityRating = 3,
                site = SiteRef("Drøbak", "Norway", "Oslofjorden", 59.661234, 10.631111),
                buddies = listOf("Alex", "Sam"),
                tanks = listOf(
                    TankEntry(
                        index = 0,
                        volumeMl = 12_000,
                        workingPressureMbar = 232_000,
                        startPressureMbar = 200_000,
                        endPressureMbar = 60_000,
                        o2Permille = 320,
                    ),
                ),
                computers = listOf(
                    ComputerEntry(
                        model = "Predator",
                        maxDepthMm = 35_900,
                        meanDepthMm = 25_400,
                        waterTempMk = 279_150,
                        samples = listOf(
                            Sample(timeOffsetSeconds = 0, depthMm = 0, temperatureMk = 279_150),
                            Sample(
                                timeOffsetSeconds = 60,
                                depthMm = 12_000,
                                temperatureMk = 279_150,
                                ndlSeconds = 1_200,
                                cnsPermille = 50,
                                ppO2Mbar = 1_200,
                                tankPressuresMbar = mapOf(0 to 180_000),
                            ),
                            Sample(
                                timeOffsetSeconds = 120,
                                depthMm = 35_900,
                                temperatureMk = 279_150,
                                stopDepthMm = 3_000,
                                stopTimeSeconds = 180,
                            ),
                        ),
                        events = listOf(Event(60, EventType.GAS_SWITCH, (32L shl 8) or 0L)),
                    ),
                    ComputerEntry(
                        model = "Petrel",
                        maxDepthMm = 36_100,
                        meanDepthMm = 25_600,
                        samples = listOf(
                            Sample(timeOffsetSeconds = 0, depthMm = 0),
                            Sample(timeOffsetSeconds = 60, depthMm = 12_100),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun roundTripsThroughAGitTree() {
        val original = sampleLog()
        val tree = format.write(original)
        val parsed = format.read(tree)

        assertEquals(1, parsed.dives.size)
        val d = parsed.dives.single()
        assertEquals(872, d.number)
        assertEquals(1_761_475_582, d.startEpochSeconds)
        assertEquals(44 * 60, d.durationSeconds)
        assertEquals("Wall dive\nsecond line", d.notes)
        assertEquals(4, d.rating)
        assertEquals(3, d.visibilityRating)
        assertEquals(listOf("Alex", "Sam"), d.buddies)
        assertEquals(35_900, d.maxDepthMm)
        assertEquals(25_400, d.meanDepthMm)
        assertEquals(279_150, d.waterTempMk)

        assertEquals("Drøbak", d.site?.name)
        assertEquals("Norway", d.site?.country)
        assertEquals("Oslofjorden", d.site?.place)
        assertEquals(59.661234, d.site?.latitude)
        assertEquals(10.631111, d.site?.longitude)

        assertEquals(1, d.tanks.size)
        assertEquals(320, d.tanks[0].o2Permille)
        assertEquals(12_000, d.tanks[0].volumeMl)
        assertEquals(200_000, d.tanks[0].startPressureMbar)
        assertEquals(60_000, d.tanks[0].endPressureMbar)

        assertEquals(2, d.computers.size)
        val predator = d.computers[0]
        assertEquals("Predator", predator.model)
        assertEquals(35_900, predator.maxDepthMm)
        assertEquals(279_150, predator.waterTempMk)
        assertEquals(3, predator.samples.size)
        assertEquals(35_900, predator.samples[2].depthMm)
        assertEquals(3_000, predator.samples[2].stopDepthMm)
        assertEquals(180, predator.samples[2].stopTimeSeconds)
        assertEquals(1_200, predator.samples[1].ndlSeconds)
        assertEquals(50, predator.samples[1].cnsPermille)
        assertEquals(1_200, predator.samples[1].ppO2Mbar)
        assertEquals(180_000, predator.samples[1].tankPressuresMbar[0])
        assertEquals(1, predator.events.size)
        assertEquals(EventType.GAS_SWITCH, predator.events[0].type)
        assertEquals((32L shl 8) or 0L, predator.events[0].value)

        assertEquals("Petrel", d.computers[1].model)
        assertEquals(36_100, d.computers[1].maxDepthMm)
    }

    @Test
    fun laysOutFilesLikeTheCloudRepo() {
        val tree = format.write(sampleLog())
        assertTrue(tree.keys.any { it == "2025/10/26-Sun-10=46=22/Dive-872" }, "dive path: ${tree.keys}")
        assertTrue(tree.keys.any { it.startsWith("01-Divesites/Site-") }, "site path: ${tree.keys}")
        assertTrue(tree.keys.any { it.endsWith("/Divecomputer-001") }, "dc path: ${tree.keys}")
    }

    @Test
    fun readsTheCloudsDiveDirectoryNames() {
        // Reuse a written dive's blobs under the directory names the cloud uses: the plain
        // "DD-Wkd-hh=mm=ss", the "~<hash>" a second dive at the same second gets, and the
        // "DD-Wkd=hh=mm=ss" early builds of this app wrote.
        val written = format.write(sampleLog())
        val dive = written.entries.first { it.key.substringAfterLast('/').startsWith("Dive-") }.value
        val computer = written.entries.first { it.key.endsWith("/Divecomputer-001") }.value
        val tree = mapOf(
            "00-Subsurface" to "version 3\n",
            "2015/09/24-Thu-13=40=00/Dive-594" to dive,
            "2015/09/24-Thu-13=40=00/Divecomputer" to computer,
            "2015/09/24-Thu-13=40=00~82f171c/Dive-595" to dive,
            "2015/09/24-Thu-13=40=00~82f171c/Divecomputer" to computer,
            "2015/09/25-Fri=10=00=00/Dive-596" to dive,
            "2015/09/25-Fri=10=00=00/Divecomputer" to computer,
        )
        val dives = format.read(tree).dives
        assertEquals(listOf(594, 595, 596), dives.map { it.number }.sortedBy { it })
        val sameSecond = dives.filter { it.number == 594 || it.number == 595 }.map { it.startEpochSeconds }.distinct()
        assertEquals(1, sameSecond.size)
        assertTrue(dives.all { d -> d.computers.single().samples.isNotEmpty() })
    }

    @Test
    fun ignoresUnknownFilesAndEmptyTree() {
        assertEquals(0, format.read(emptyMap()).dives.size)
        val tree = format.write(sampleLog()) + ("README.md" to "not dive data") + (".git/config" to "x")
        assertEquals(1, format.read(tree).dives.size)
    }

    private fun withSerial(log: DiveLog, serial: String) = log.copy(
        dives = log.dives.map { d -> d.copy(computers = d.computers.mapIndexed { i, c -> if (i == 0) c.copy(serial = serial) else c }) },
    )

    @Test
    fun readsSerialsFromTheSettingsFile() {
        val tree = format.write(sampleLog()).mapValues { (path, content) ->
            if (path.endsWith("/Divecomputer-001")) "deviceid 1a2b3c4d\n$content" else content
        } + ("00-Subsurface" to "version 3\ndivecomputerid \"Shearwater Predator\" deviceid=1a2b3c4d serial=\"A1B2C3D4\" firmware=\"84\"\n")
        val computers = format.read(tree).dives.single().computers
        assertEquals("A1B2C3D4", computers[0].serial)
        assertEquals(null, computers[1].serial)
    }

    @Test
    fun keepsDivesThatStartTheSameSecond() {
        val dive = DiveEntry(startEpochSeconds = 1_700_000_000, durationSeconds = 600, notes = "first")
        val tree = format.write(DiveLog(listOf(dive, dive.copy(notes = "second"))))
        assertEquals(2, tree.keys.count { it.substringAfterLast('/').startsWith("Dive") && !it.contains("Divecomputer") })
        assertEquals(setOf("first", "second"), format.read(tree).dives.map { it.notes }.toSet())
    }

    @Test
    fun readsTagsGeoAndGasSwitchesByCylinder() {
        val tree = mapOf(
            "00-Subsurface" to "version 3\n",
            "01-Divesites/Site-0a0b0c0d" to "name \"The Wall\"\ngps 59.660000 10.630000\ngeo cat 2 origin 0 \"Norway\"\ngeo cat 3 origin 0 \"Drøbak\"\n",
            "2024/05/30-Thu-06=14=00/Dive-7" to "duration 40:00 min\ntags \"wreck\", \"night\"\ndivesiteid 0a0b0c0d\n" +
                "cylinder vol=12.0l\ncylinder vol=7.0l o2=50.0%\n",
            "2024/05/30-Thu-06=14=00/Divecomputer" to "model \"X\"\nevent 0:10 type=11 value=50 name=\"gaschange\" cylinder=1\n" +
                "  0:00 1.0m 12.0°C ndl=20:00\n  0:10 5.0m\n",
        )
        val d = format.read(tree).dives.single()
        assertEquals(listOf("wreck", "night"), d.tags)
        assertEquals(SiteRef("The Wall", "Norway", "Drøbak", 59.66, 10.63), d.site)
        assertEquals(listOf(null, 500), d.tanks.map { it.o2Permille })
        assertEquals(GasSwitch.value(50, 0), d.computers.single().events.single().value)
        assertEquals(Sample(10, depthMm = 5_000, temperatureMk = 285_150, ndlSeconds = 1_200), d.computers.single().samples[1])
    }

    @Test
    fun writesTheSettingsFile() {
        val tree = format.write(withSerial(sampleLog(), "abc"))
        assertTrue(tree.getValue("00-Subsurface").startsWith("version 3\n"))
        assertTrue(tree.getValue("00-Subsurface").contains("deviceid=363e99a9 serial=\"abc\""))
    }
}
