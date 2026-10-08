package no.synth.divelog.core.formats

import no.synth.divelog.core.formats.RoundTripFixture.log
import no.synth.divelog.core.formats.RoundTripFixture.manual
import no.synth.divelog.core.formats.RoundTripFixture.plain
import no.synth.divelog.core.formats.RoundTripFixture.rich
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.Sample
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every format against [RoundTripFixture]: write, read and write again gives the same
 * bytes, and reading gives back the logbook less only what the format cannot carry.
 */
class RoundTripTest {

    // Losses every format shares: none of them stores a computed ceiling or the active
    // gas per sample (gas changes are events).
    private fun Sample.withoutCeilingAndGas() = copy(ceilingMm = null, activeGasIndex = null)
    private fun ComputerEntry.withoutCeilingAndGas() = copy(samples = samples.map { it.withoutCeilingAndGas() })
    private fun DiveEntry.withoutCeilingAndGas() = copy(computers = computers.map { it.withoutCeilingAndGas() })

    // Subsurface keeps visibility as 0..5 stars only; the other formats as a distance only.
    private fun DiveEntry.subsurface() = withoutCeilingAndGas().copy(visibility = null)

    private fun assertStable(format: DiveFormat) {
        val first = format.write(log)
        assertEquals(first, format.write(format.read(first)))
    }

    @Test
    fun subsurfaceXmlIsStable() = assertStable(SubsurfaceXml())

    @Test
    fun subsurfaceXmlKeepsEverythingButCeilingAndActiveGas() {
        val read = SubsurfaceXml().read(SubsurfaceXml().write(log))
        assertEquals(DiveLog(log.dives.map { it.subsurface() }), read)
    }

    @Test
    fun gitTreeIsStable() {
        val format = GitLogFormat()
        val first = format.write(log)
        assertEquals(first, format.write(format.read(first)))
    }

    @Test
    fun gitTreeKeepsEverythingButCeilingAndActiveGas() {
        val read = GitLogFormat().read(GitLogFormat().write(log))
        assertEquals(DiveLog(log.dives.map { it.subsurface() }), read)
    }

    @Test
    fun uddfIsStable() = assertStable(UddfFormat())

    @Test
    fun uddfKeepsOneProfilePerDiveAndNoTagsOrWorkingPressures() {
        val read = UddfFormat().read(UddfFormat().write(log))
        // UDDF has no tags, no working pressure on tankdata, one profile per dive, and
        // alarms only for ascent, deco, surface and errors (no bookmarks or others).
        fun DiveEntry.uddf() = copy(
            visibilityRating = null,
            tags = emptyList(),
            tanks = tanks.map { it.copy(workingPressureMbar = null) },
            computers = computers.take(1).map { c ->
                c.withoutCeilingAndGas().copy(
                    airTempMk = airTempMk,
                    events = c.events.filter { it.type != EventType.BOOKMARK && it.type != EventType.OTHER },
                )
            },
        )
        assertEquals(DiveLog(listOf(rich.uddf(), plain.uddf(), manual.uddf())), read)
    }

    @Test
    fun macDiveIsStable() = assertStable(MacDiveXml())

    @Test
    fun macDiveKeepsTheLocalTimeAndOneComputersBasicProfile() {
        val read = MacDiveXml().read(MacDiveXml().write(log))
        // MacDive has local time only, one computer per dive, one tank pressure per sample,
        // no stop, CNS or ceiling samples, and events by name without a value.
        fun DiveEntry.macDive() = copy(
            startEpochSeconds = startEpochSeconds + utcOffsetSeconds,
            utcOffsetSeconds = 0,
            visibilityRating = null,
            computers = computers.take(1).map { c ->
                c.copy(
                    samples = c.samples.map { s ->
                        s.withoutCeilingAndGas().copy(
                            stopDepthMm = null,
                            stopTimeSeconds = null,
                            cnsPermille = null,
                            tankPressuresMbar = s.tankPressuresMbar.filterKeys { it == 0 },
                        )
                    },
                    events = c.events.map { if (it.type == EventType.GAS_SWITCH) it else it.copy(value = null) },
                )
            },
        )
        assertEquals(DiveLog(listOf(rich.macDive(), plain.macDive(), manual.macDive())), read)
    }
}
