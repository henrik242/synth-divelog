package no.synth.divelog.core.formats

import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MacDiveXmlTest {
    private val format = MacDiveXml()

    private fun sampleLog() = DiveLog(
        listOf(
            DiveEntry(
                number = 42,
                startEpochSeconds = 1_761_475_582, // 2025-10-26 10:46:22
                durationSeconds = 44 * 60,
                maxDepthMm = 35_900,
                meanDepthMm = 25_400,
                waterTempMk = 279_150, // 6.00 C
                airTempMk = 285_150, // 12.00 C
                notes = "Wall dive with a <bracket> & ampersand",
                rating = 4,
                visibility = 8,
                site = SiteRef("Testholmen", "Testland", "Testfjorden", 59.66, 10.63),
                buddies = listOf("Alex", "Sam"),
                tanks = listOf(
                    TankEntry(
                        index = 0,
                        volumeMl = 12_000,
                        workingPressureMbar = 232_000,
                        startPressureMbar = 200_000,
                        endPressureMbar = 60_000,
                        o2Permille = 320,
                        hePermille = 0,
                    ),
                ),
                computers = listOf(
                    ComputerEntry(
                        model = "Testputer 2",
                        maxDepthMm = 35_900,
                        meanDepthMm = 25_400,
                        waterTempMk = 279_150,
                        samples = listOf(
                            Sample(timeOffsetSeconds = 0, depthMm = 0, temperatureMk = 279_150),
                            Sample(
                                timeOffsetSeconds = 60,
                                depthMm = 12_000,
                                temperatureMk = 279_150,
                                ppO2Mbar = 1_260, // 1.26 bar
                                ndlSeconds = 20 * 60, // 20 min no-deco
                                tankPressuresMbar = mapOf(0 to 180_000),
                            ),
                            Sample(timeOffsetSeconds = 120, depthMm = 35_900, temperatureMk = 279_150),
                        ),
                        events = listOf(
                            Event(60, EventType.GAS_SWITCH, ((32L shl 8) or 0L)),
                            Event(90, EventType.ASCENT_RATE),
                            Event(100, EventType.BOOKMARK),
                        ),
                    ),
                ),
            ),
        ),
    )

    @Test
    fun roundTripsADive() {
        val original = sampleLog()
        val xml = format.write(original)
        val parsed = format.read(xml)

        assertEquals(1, parsed.dives.size)
        val d = parsed.dives.single()
        assertEquals(42, d.number)
        assertEquals(1_761_475_582, d.startEpochSeconds)
        assertEquals(44 * 60, d.durationSeconds)
        assertEquals(35_900, d.maxDepthMm)
        assertEquals(25_400, d.meanDepthMm)
        assertEquals(279_150, d.waterTempMk)
        assertEquals(285_150, d.airTempMk)
        assertEquals("Wall dive with a <bracket> & ampersand", d.notes)
        assertEquals(4, d.rating)
        assertEquals(8, d.visibility)
        assertEquals(listOf("Alex", "Sam"), d.buddies)

        assertEquals("Testholmen", d.site?.name)
        assertEquals("Testland", d.site?.country)
        assertEquals("Testfjorden", d.site?.place)
        assertEquals(59.66, d.site?.latitude)
        assertEquals(10.63, d.site?.longitude)

        assertEquals(1, d.tanks.size)
        val tank = d.tanks[0]
        assertEquals(320, tank.o2Permille)
        assertEquals(0, tank.hePermille)
        assertEquals(12_000, tank.volumeMl)
        assertEquals(232_000, tank.workingPressureMbar)
        assertEquals(200_000, tank.startPressureMbar)
        assertEquals(60_000, tank.endPressureMbar)

        assertEquals(1, d.computers.size)
        val c = d.computers[0]
        assertEquals("Testputer 2", c.model)
        assertEquals(3, c.samples.size)
        val mid = c.samples[1]
        assertEquals(60, mid.timeOffsetSeconds)
        assertEquals(12_000, mid.depthMm)
        assertEquals(279_150, mid.temperatureMk)
        assertEquals(1_260, mid.ppO2Mbar) // ppo2 bar -> millibar
        assertEquals(20 * 60, mid.ndlSeconds) // ndt minutes -> seconds
        assertEquals(180_000, mid.tankPressuresMbar[0])

        assertEquals(3, c.events.size)
        assertEquals(EventType.GAS_SWITCH, c.events[0].type)
        assertEquals((32L shl 8), c.events[0].value)
        assertEquals(EventType.ASCENT_RATE, c.events[1].type)
        assertEquals(EventType.BOOKMARK, c.events[2].type)
    }

    @Test
    fun writesMacDiveDoctypeAndSchema() {
        val xml = format.write(sampleLog())
        assertTrue(xml.contains("mac-dive"), "DOCTYPE should carry the mac-dive marker for detection")
        assertTrue(xml.contains("<schema>2.2.0</schema>"))
        assertTrue(xml.contains("<units>Metric</units>"))
    }

    @Test
    fun ignoresUnknownElementsWithoutFailing() {
        val xml = format.write(sampleLog())
            .replace("<dives>", "<dives><weirdthing>hello</weirdthing>")
        val parsed = format.read(xml)
        assertEquals(1, parsed.dives.size)
    }
}
