package no.synth.divelog.core.formats

import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.Sample
import kotlin.test.Test
import kotlin.test.assertEquals

class UddfFormatTest {
    private val format = UddfFormat()

    private fun profile() = listOf(
        Sample(timeOffsetSeconds = 0, depthMm = 0, temperatureMk = 279_150),
        Sample(timeOffsetSeconds = 60, depthMm = 12_000, temperatureMk = 279_150),
        Sample(timeOffsetSeconds = 120, depthMm = 35_900, temperatureMk = 279_150, stopDepthMm = 3_000, stopTimeSeconds = 180),
    )

    @Test
    fun roundTripsADive() {
        val original = DiveLog(
            listOf(
                DiveEntry(
                    number = 872,
                    startEpochSeconds = 1_761_475_582,
                    durationSeconds = 2_640,
                    notes = "Wall dive",
                    rating = 4,
                    visibility = 8_000,
                    site = SiteRef("Drøbak", "Norway", "Oslofjorden", 59.66, 10.63),
                    buddies = listOf("Alex", "Sam"),
                    gasMixes = listOf(GasMix(o2Permille = 320, hePermille = 0)),
                    computers = listOf(
                        ComputerEntry(
                            maxDepthMm = 35_900,
                            meanDepthMm = 25_400,
                            waterTempMk = 279_150,
                            samples = profile(),
                            events = listOf(Event(60, EventType.GAS_SWITCH, (32L shl 8))),
                        ),
                    ),
                ),
            ),
        )

        val d = format.read(format.write(original)).dives.single()
        assertEquals(872, d.number)
        assertEquals(1_761_475_582, d.startEpochSeconds)
        assertEquals(2_640, d.durationSeconds)
        assertEquals("Wall dive", d.notes)
        assertEquals(4, d.rating)
        assertEquals(8_000, d.visibility)
        assertEquals("Drøbak", d.site?.name)
        assertEquals("Norway", d.site?.country)
        assertEquals(59.66, d.site?.latitude)
        assertEquals(listOf("Alex", "Sam"), d.buddies)

        val c = d.computers.single()
        assertEquals(35_900, c.maxDepthMm)
        assertEquals(279_150, c.waterTempMk)
        assertEquals(3, c.samples.size)
        assertEquals(35_900, c.samples[2].depthMm)
        assertEquals(3_000, c.samples[2].stopDepthMm)
        assertEquals(180, c.samples[2].stopTimeSeconds)
        assertEquals(1, c.events.size)
        assertEquals(EventType.GAS_SWITCH, c.events[0].type)
        assertEquals(60, c.events[0].timeOffsetSeconds)
        assertEquals(32L shl 8, c.events[0].value)
    }

    @Test
    fun exportsSecondaryProfileWhenPrimaryHasNone() {
        val original = DiveLog(
            listOf(
                DiveEntry(
                    number = 1,
                    startEpochSeconds = 1_761_475_582,
                    durationSeconds = 2_640,
                    computers = listOf(
                        ComputerEntry(model = "primary-no-profile", maxDepthMm = 10_000),
                        ComputerEntry(model = "secondary", maxDepthMm = 35_900, samples = profile()),
                    ),
                ),
            ),
        )
        val d = format.read(format.write(original)).dives.single()
        // The exported profile is the secondary computer's (3 samples, 35.9 m).
        assertEquals(3, d.computers.single().samples.size)
        assertEquals(35_900, d.maxDepthMm)
    }
}
