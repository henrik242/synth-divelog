package no.synth.divelog.core.formats

import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.Sample
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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

    @Test
    fun roundTripsTanksAndTheirGases() {
        val dive = DiveEntry(
            number = 1,
            startEpochSeconds = 1_700_000_000,
            durationSeconds = 3_000,
            tanks = listOf(
                TankEntry(index = 0, volumeMl = 12_000, startPressureMbar = 210_000, endPressureMbar = 60_000, o2Permille = 320, hePermille = 0),
                TankEntry(index = 1, volumeMl = 7_000, o2Permille = 500, hePermille = 0),
            ),
        )
        val read = UddfFormat().read(UddfFormat().write(DiveLog(listOf(dive)))).dives.single().tanks
        assertEquals(dive.tanks.map { it.copy(workingPressureMbar = null) }, read)
    }

    @Test
    fun roundTripsTheDiveComputer() {
        val dive = DiveEntry(
            startEpochSeconds = 1_700_000_000,
            durationSeconds = 3_000,
            computers = listOf(ComputerEntry(model = "Suunto HelO2", serial = "94803", maxDepthMm = 20_000)),
        )
        val read = UddfFormat().read(UddfFormat().write(DiveLog(listOf(dive)))).dives.single().computers.single()
        assertEquals("Suunto HelO2", read.model)
        assertEquals("94803", read.serial)
    }

    @Test
    fun takesTheComputersOwnNameNotItsShopsOrMakers() {
        val xml = """<uddf version="3.2.1"><diver><owner id="o"><equipment>
            <divecomputer id="dc"><name>Suunto ZOOP</name><manufacturer id="m"><name>Suunto</name></manufacturer>
            <serialnumber>123</serialnumber><purchase><shop><name>Some Shop</name></shop></purchase></divecomputer>
            </equipment></owner></diver><profiledata><repetitiongroup><dive>
            <informationbeforedive><datetime>2020-01-01T10:00:00</datetime></informationbeforedive>
            <informationafterdive><diveduration>1800.0</diveduration><equipmentused><link ref="dc"/></equipmentused></informationafterdive>
            </dive></repetitiongroup></profiledata></uddf>"""
        val dive = UddfFormat().read(xml).dives.single()
        assertEquals(1_800, dive.durationSeconds)
        assertEquals("Suunto ZOOP", dive.computers.single().model)
        assertEquals("123", dive.computers.single().serial)
    }

    private fun startOf(datetime: String): Pair<Long, Int> {
        val xml = """<uddf version="3.2.1"><profiledata><repetitiongroup><dive>
            <informationbeforedive><datetime>$datetime</datetime></informationbeforedive>
            </dive></repetitiongroup></profiledata></uddf>"""
        val dive = UddfFormat().read(xml).dives.single()
        return dive.startEpochSeconds to dive.utcOffsetSeconds
    }

    @Test
    fun readsTheDateTimeAsIso8601() {
        val wallClock = 1_224_950_700L // 2008-10-25 16:05 as wall clock
        // No zone: local time, kept as the wall clock.
        assertEquals(wallClock to 0, startOf("2008-10-25T16:05:00"))
        assertEquals(wallClock to 0, startOf("2008-10-25T16:05"))
        // A zone gives the real instant and keeps the offset.
        assertEquals(wallClock to 0, startOf("2008-10-25T16:05Z"))
        assertEquals(wallClock to 0, startOf("20081025T1605+0000"))
        assertEquals(wallClock - 7_200 to 7_200, startOf("2008-10-25T16:05:00+02:00"))
        assertEquals(wallClock + 18_000 to -18_000, startOf("2008-10-25T16:05-05"))
    }

    @Test
    fun writesTheOffsetWhenTheDiveHasOne() {
        val dive = DiveEntry(startEpochSeconds = 1_224_943_500, utcOffsetSeconds = 7_200, durationSeconds = 600)
        val xml = format.write(DiveLog(listOf(dive)))
        assertTrue(xml.contains("<datetime>2008-10-25T16:05:00+02:00</datetime>"), xml)
        val back = format.read(xml).dives.single()
        assertEquals(dive.startEpochSeconds to dive.utcOffsetSeconds, back.startEpochSeconds to back.utcOffsetSeconds)

        val local = format.write(DiveLog(listOf(dive.copy(startEpochSeconds = 1_224_950_700, utcOffsetSeconds = 0))))
        assertTrue(local.contains("<datetime>2008-10-25T16:05:00</datetime>"), local)
    }

    private fun siteOf(siteXml: String): SiteRef? {
        val xml = """<uddf xmlns="http://www.streit.cc/uddf/3.2/" version="3.2.1"><divesite>$siteXml</divesite>
            <profiledata><repetitiongroup><dive><informationbeforedive><link ref="s1"/>
            <datetime>2020-01-01T10:00:00</datetime></informationbeforedive></dive></repetitiongroup></profiledata></uddf>"""
        return UddfFormat().read(xml).dives.single().site
    }

    @Test
    fun readsSitesAsTheSpecLaysThemOut() {
        val site = siteOf(
            """<site id="s1"><name>The Wall</name><geography><address><country>Norway</country></address>
            <location>Drøbak</location><latitude>59.66</latitude><longitude>10.63</longitude></geography></site>""",
        )
        assertEquals(SiteRef("The Wall", "Norway", "Drøbak", 59.66, 10.63), site)
    }

    @Test
    fun readsSitesFromEarlierExportsOfThisApp() {
        assertEquals(SiteRef("The Wall", "Norway", "Drøbak"), siteOf("""<site id="s1" name="Norway / Drøbak / The Wall"/>"""))
        assertEquals(SiteRef("Somewhere"), siteOf("""<site id="s1" name="Somewhere"/>"""))
    }

    @Test
    fun writesSitesAsTheSpecLaysThemOut() {
        val dive = DiveEntry(startEpochSeconds = 0, durationSeconds = 60, site = SiteRef("The Wall", "Norway", "Drøbak"))
        val xml = format.write(DiveLog(listOf(dive)))
        assertTrue(xml.contains("<name>The Wall</name>"), xml)
        assertTrue(xml.contains("<address><country>Norway</country></address><location>Drøbak</location>"), xml)
        assertTrue(xml.contains("xmlns=\"http://www.streit.cc/uddf/3.2/\""), xml)
    }

    @Test
    fun readsWaypointsAsTheSpecWritesThem() {
        val xml = """<uddf xmlns="http://www.streit.cc/uddf/3.2/" version="3.2.1">
            <diver><buddy id="b1"><personal><firstname>Ola</firstname><lastname>Nordmann</lastname></personal></buddy></diver>
            <profiledata><repetitiongroup><dive id="d1">
            <informationbeforedive><datetime>2020-01-01T10:00:00</datetime><airtemperature>293.15</airtemperature><link ref="b1"/></informationbeforedive>
            <tankdata id="t1"><tankvolume>0.012</tankvolume></tankdata><tankdata id="t2"><tankvolume>0.007</tankvolume></tankdata>
            <samples><waypoint><alarm level="2.0">ascent</alarm><depth>10.0</depth><divetime>60.0</divetime>
            <calculatedpo2>1.2e5</calculatedpo2><cns>12.5</cns><decostop kind="mandatory" decodepth="3.0" duration="120.0"/>
            <nodecotime>0.0</nodecotime><tankpressure>20000000.0</tankpressure><tankpressure ref="t2">0.0</tankpressure>
            <tankpressure ref="t2">18000000.0</tankpressure></waypoint></samples>
            <informationafterdive><rating><ratingvalue>10</ratingvalue></rating></informationafterdive>
            </dive></repetitiongroup></profiledata></uddf>"""
        val d = UddfFormat().read(xml).dives.single()
        assertEquals(listOf("Ola Nordmann"), d.buddies)
        assertEquals(293_150, d.airTempMk)
        assertEquals(5, d.rating)
        val c = d.computers.single()
        assertEquals(
            Sample(60, depthMm = 10_000, ppO2Mbar = 1_200, ndlSeconds = 0, stopDepthMm = 3_000, stopTimeSeconds = 120, cnsPermille = 125, tankPressuresMbar = mapOf(0 to 200_000, 1 to 180_000)),
            c.samples.single(),
        )
        assertEquals(listOf(Event(60, EventType.ASCENT_RATE, 2)), c.events)
    }

    @Test
    fun writesStopsAndTankPressuresAsTheSpecDoes() {
        val dive = DiveEntry(
            startEpochSeconds = 0,
            durationSeconds = 60,
            tanks = listOf(TankEntry(0, o2Permille = 210, hePermille = 0), TankEntry(1, o2Permille = 500, hePermille = 0)),
            computers = listOf(ComputerEntry(samples = listOf(Sample(0, depthMm = 9_000, stopDepthMm = 3_000, stopTimeSeconds = 60, tankPressuresMbar = mapOf(1 to 150_000))))),
        )
        val xml = format.write(DiveLog(listOf(dive)))
        assertTrue(xml.contains("<decostop kind=\"mandatory\" decodepth=\"3.0\" duration=\"60\""), xml)
        assertTrue(xml.contains("<tankdata id=\"dive1_tank2\">"), xml)
        assertTrue(xml.contains("<tankpressure ref=\"dive1_tank2\">15000000</tankpressure>"), xml)
        assertTrue(xml.contains("<dive id=\"dive1\">"), xml)
    }

    @Test
    fun notesKeepEveryParagraph() {
        val dive = DiveEntry(startEpochSeconds = 1_761_475_582, durationSeconds = 60, notes = "First\n\nThird")
        val xml = format.write(DiveLog(listOf(dive)))
        assertTrue(xml.contains("<para>First</para>"), xml)
        assertEquals("First\n\nThird", format.read(xml).dives.single().notes)

        val twoParas = xml.replace(Regex("<notes>.*</notes>"), "<notes><para>One</para><para>Two</para></notes>")
        assertEquals("One\nTwo", format.read(twoParas).dives.single().notes)
    }
}
