package no.synth.divelog.core.formats

import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.Sample
import kotlin.test.Test
import kotlin.test.assertEquals

class SubsurfaceXmlTest {
    private val format = SubsurfaceXml()

    private fun sampleLog() = DiveLog(
        listOf(
            DiveEntry(
                number = 872,
                startEpochSeconds = 1_761_475_582, // 2025-10-26 10:46:22
                durationSeconds = 44 * 60,
                notes = "Wall dive",
                rating = 4,
                visibility = 8,
                site = SiteRef("Drøbak", "Norway", "Oslofjorden", 59.66, 10.63),
                buddies = listOf("Alex", "Sam"),
                tanks = listOf(TankEntry(index = 0, volumeMl = 12_000, workingPressureMbar = 232_000, startPressureMbar = 200_000, endPressureMbar = 60_000, o2Permille = 320)),
                computers = listOf(
                    ComputerEntry(
                        model = "Predator",
                        maxDepthMm = 35_900,
                        meanDepthMm = 25_400,
                        waterTempMk = 279_150,
                        samples = listOf(
                            Sample(timeOffsetSeconds = 0, depthMm = 0, temperatureMk = 279_150),
                            Sample(timeOffsetSeconds = 60, depthMm = 12_000, temperatureMk = 279_150, ndlSeconds = 1_200),
                            Sample(timeOffsetSeconds = 120, depthMm = 35_900, temperatureMk = 279_150, stopDepthMm = 3_000, stopTimeSeconds = 180),
                        ),
                        events = listOf(Event(60, EventType.GAS_SWITCH, ((32L shl 8) or 0L))),
                    ),
                    ComputerEntry(
                        model = "Petrel",
                        maxDepthMm = 36_100,
                        meanDepthMm = 25_600,
                        waterTempMk = 278_150,
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
    fun roundTripsADiveWithTwoComputers() {
        val original = sampleLog()
        val xml = format.write(original)
        val parsed = format.read(xml)

        assertEquals(1, parsed.dives.size)
        val d = parsed.dives.single()
        assertEquals(872, d.number)
        assertEquals(1_761_475_582, d.startEpochSeconds)
        assertEquals(44 * 60, d.durationSeconds)
        assertEquals("Wall dive", d.notes)
        assertEquals(4, d.rating)
        assertEquals(8, d.visibility)
        assertEquals(listOf("Alex", "Sam"), d.buddies)

        assertEquals("Drøbak", d.site?.name)
        assertEquals("Norway", d.site?.country)
        assertEquals("Oslofjorden", d.site?.place)
        assertEquals(59.66, d.site?.latitude)

        assertEquals(1, d.tanks.size)
        assertEquals(320, d.tanks[0].o2Permille)
        assertEquals(200_000, d.tanks[0].startPressureMbar)

        assertEquals(2, d.computers.size)
        val predator = d.computers[0]
        assertEquals("Predator", predator.model)
        assertEquals(35_900, predator.maxDepthMm)
        assertEquals(279_150, predator.waterTempMk)
        assertEquals(3, predator.samples.size)
        assertEquals(35_900, predator.samples[2].depthMm)
        assertEquals(3_000, predator.samples[2].stopDepthMm)
        assertEquals(1, predator.events.size)
        assertEquals(EventType.GAS_SWITCH, predator.events[0].type)

        assertEquals("Petrel", d.computers[1].model)
        assertEquals(36_100, d.computers[1].maxDepthMm)
    }

    @Test
    fun ignoresUnknownElementsWithoutFailing() {
        val xml = format.write(sampleLog())
            .replace("<dives>", "<dives><weirdthing foo='bar'>hello</weirdthing>")
        val parsed = format.read(xml)
        assertEquals(1, parsed.dives.size)
    }
}
