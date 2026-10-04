package no.synth.divelog.core.formats

import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.Sample
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
                visibility = 8,
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
        assertEquals(8, d.visibility)
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
        assertTrue(tree.keys.any { it == "2025/10/26-Sun=10=46=22/Dive-872" }, "dive path: ${tree.keys}")
        assertTrue(tree.keys.any { it.startsWith("01-Divesites/Site-") }, "site path: ${tree.keys}")
        assertTrue(tree.keys.any { it.endsWith("/Divecomputer-001") }, "dc path: ${tree.keys}")
    }

    @Test
    fun ignoresUnknownFilesAndEmptyTree() {
        assertEquals(0, format.read(emptyMap()).dives.size)
        val tree = format.write(sampleLog()) + ("README.md" to "not dive data") + (".git/config" to "x")
        assertEquals(1, format.read(tree).dives.size)
    }
}
