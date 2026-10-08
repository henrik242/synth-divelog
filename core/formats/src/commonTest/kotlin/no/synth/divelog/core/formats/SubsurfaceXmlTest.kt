package no.synth.divelog.core.formats

import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.Sample
import no.synth.divelog.core.model.GasSwitch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
                tags = listOf("boat", "deep"),
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
        assertEquals(listOf("boat", "deep"), d.tags)

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

    private fun withSerial(log: DiveLog, serial: String) = log.copy(
        dives = log.dives.map { d -> d.copy(computers = d.computers.mapIndexed { i, c -> if (i == 0) c.copy(serial = serial) else c }) },
    )

    @Test
    fun roundTripsTheComputerSerial() {
        val xml = format.write(withSerial(sampleLog(), "A1B2C3D4"))
        assertTrue(xml.contains("<divecomputerid"), xml)
        val computers = format.read(xml).dives.single().computers
        assertEquals("A1B2C3D4", computers[0].serial)
        assertEquals(null, computers[1].serial)
    }

    @Test
    fun anUnknownDeviceIdHasNoSerial() {
        val xml = format.write(sampleLog()).replace("<divecomputer ", "<divecomputer deviceid='ffffffff' ")
        assertTrue(format.read(xml).dives.single().computers.all { it.serial == null })
    }

    private fun diveOf(body: String, extra: String = "") = format.read(
        "<divelog program='subsurface' version='3'>$extra<dives><dive date='2024-05-30' time='06:14:00' duration='40:00 min'>$body</dive></dives></divelog>",
    ).dives.single()

    @Test
    fun sampleValuesCarryForwardButPressuresDoNot() {
        val d = diveOf(
            "<divecomputer model='X'>" +
                "<sample time='0:10 min' depth='5.0 m' temp='12.0 C' pressure0='200.0 bar' ndl='20:00 min' cns='3%' dc_supplied_ppo2='1.1 bar' />" +
                "<sample time='0:20 min' depth='6.0 m' />" +
                "</divecomputer>",
        )
        val second = d.computers.single().samples[1]
        assertEquals(Sample(20, depthMm = 6_000, temperatureMk = 285_150, ppO2Mbar = 1_100, ndlSeconds = 1_200, cnsPermille = 30), second)
    }

    @Test
    fun readsPressuresPerSensorThroughTheTankMapping() {
        val d = diveOf(
            "<divecomputer model='X'><tanksensormapping sensorid='3' cylinderindex='1' />" +
                "<sample time='0:10 min' depth='5.0 m' pressure0='200.0 bar' pressure3='180.5 bar' /></divecomputer>",
        )
        assertEquals(mapOf(0 to 200_000, 1 to 180_500), d.computers.single().samples.single().tankPressuresMbar)
    }

    @Test
    fun readsTheUtcOffsetAndSerialFromExtraData() {
        val d = diveOf(
            "<divecomputer model='X' deviceid='363e99a9'><extradata key='Serial' value='abc' />" +
                "<extradata key='Time offset from UTC [s]' value='+7200' /></divecomputer>",
        )
        assertEquals(7_200, d.utcOffsetSeconds)
        assertEquals(1_717_042_440, d.startEpochSeconds) // 06:14 at UTC+2
        assertEquals("abc", d.computers.single().serial)
    }

    @Test
    fun theDeviceIdIsTheSerialsSha1() {
        assertEquals("363e99a9", SubsurfaceShared.deviceId("abc"))
    }

    @Test
    fun readsSiteTaxonomyAndCommaSeparatedBuddies() {
        val d = format.read(
            "<divelog><divesites><site uuid=' 1a2b3c4' name='The Wall' gps='59.660000 10.630000'>" +
                "<geo cat='2' origin='0' value='Norway' /><geo cat='4' origin='0' value='Drøbak' /></site></divesites>" +
                "<dives><dive divesiteid='1a2b3c4' date='2024-05-30' time='06:14:00'><buddy>Ola Nordmann, Kari</buddy></dive></dives></divelog>",
        ).dives.single()
        assertEquals(SiteRef("The Wall", "Norway", "Drøbak", 59.66, 10.63), d.site)
        assertEquals(listOf("Ola Nordmann", "Kari"), d.buddies)
    }

    @Test
    fun aCylinderWithoutAMixHasNoGasAndSwitchesResolveThroughTheCylinder() {
        val d = diveOf(
            "<cylinder size='12.0 l' /><cylinder size='7.0 l' o2='50.0%' />" +
                "<divecomputer model='X'>" +
                "<event time='0:10 min' type='11' name='gaschange' cylinder='1' />" +
                "<event time='0:20 min' type='25' value='2949138' name='gaschange' />" +
                "<event time='0:30 min' type='11' name='gaschange' />" +
                "</divecomputer>",
        )
        assertEquals(listOf(null, 500), d.tanks.map { it.o2Permille })
        val gases = d.computers.single().events.map { GasSwitch.o2Percent(it.value ?: 0) to GasSwitch.hePercent(it.value ?: 0) }
        assertEquals(listOf(50 to 0, 18 to 45, 21 to 0), gases)
    }

    @Test
    fun theDiveTemperatureDefaultsToTheComputersMean() {
        val d = diveOf(
            "<divecomputer model='A'><temperature water='8.0 C' /></divecomputer>" +
                "<divecomputer model='B'><temperature water='9.0 C' /></divecomputer>",
        )
        assertEquals(281_650, d.waterTempMk)
    }
}
