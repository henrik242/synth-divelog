package no.synth.divelog.core.formats

import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.GasSwitch
import no.synth.divelog.core.model.Sample

/**
 * A logbook that uses every field the format model has, for the round-trip tests. Values
 * sit on the coarsest grid any format writes (10 mm, 10 mK, 10 mbar, whole percent, whole
 * minutes of no-deco time) so that only real losses show up.
 */
internal object RoundTripFixture {
    private fun sample(t: Int, depth: Int, temp: Int, ppO2: Int, ndl: Int, ceiling: Int, stopDepth: Int, stopTime: Int, cns: Int, gas: Int, p0: Int, p1: Int) =
        Sample(
            timeOffsetSeconds = t,
            depthMm = depth,
            temperatureMk = temp,
            ppO2Mbar = ppO2,
            ndlSeconds = ndl,
            ceilingMm = ceiling,
            stopDepthMm = stopDepth,
            stopTimeSeconds = stopTime,
            cnsPermille = cns,
            activeGasIndex = gas,
            tankPressuresMbar = mapOf(0 to p0, 1 to p1),
        )

    val primary = ComputerEntry(
        model = "Shearwater Petrel 3",
        serial = "A1B2C3D4",
        maxDepthMm = 45_670,
        meanDepthMm = 21_340,
        waterTempMk = 280_650,
        airTempMk = 291_150,
        samples = listOf(
            sample(0, 1_000, 285_150, 200, 5_940, 0, 0, 0, 10, 0, 220_000, 200_000),
            sample(10, 20_500, 284_150, 1_190, 1_200, 0, 0, 0, 20, 0, 215_500, 200_000),
            sample(20, 45_670, 280_650, 1_390, 0, 9_000, 9_000, 120, 150, 0, 180_250, 200_000),
            sample(30, 21_000, 280_650, 1_550, 0, 6_000, 6_000, 180, 230, 1, 150_000, 190_010),
            sample(40, 0, 281_650, 1_600, 600, 0, 0, 0, 240, 1, 60_000, 120_000),
        ),
        events = listOf(
            Event(10, EventType.GAS_SWITCH, GasSwitch.value(18, 45)),
            Event(20, EventType.BOOKMARK),
            Event(20, EventType.WARNING, 80),
            Event(30, EventType.ASCENT_RATE),
            Event(30, EventType.DECO),
            Event(30, EventType.GAS_SWITCH, GasSwitch.value(50, 0)),
            Event(40, EventType.SURFACE),
            Event(40, EventType.OTHER, 7),
        ),
    )

    val secondary = ComputerEntry(
        model = "Suunto HelO2",
        serial = "12345678",
        maxDepthMm = 45_210,
        meanDepthMm = 21_000,
        waterTempMk = 281_150,
        samples = listOf(
            Sample(timeOffsetSeconds = 0, depthMm = 1_200, temperatureMk = 285_150),
            Sample(timeOffsetSeconds = 20, depthMm = 45_210, temperatureMk = 281_150),
        ),
        startEpochSeconds = 1_717_042_450,
        durationSeconds = 3_700,
    )

    val rich = DiveEntry(
        number = 42,
        // 2024-05-30 06:14 local, UTC+2
        startEpochSeconds = 1_717_042_440,
        utcOffsetSeconds = 7_200,
        durationSeconds = 3_725,
        maxDepthMm = 45_670,
        meanDepthMm = 21_340,
        waterTempMk = 280_650,
        airTempMk = 291_150,
        notes = "Line one: <tags> & \"quotes\" 'apos' \\ backslash\nLine two\n\nAfter a blank line ]]> æøå",
        rating = 4,
        visibility = 15_000,
        visibilityRating = 3,
        site = SiteRef("Blue Hole", country = "Norway", place = "Gulen", latitude = 60.912345, longitude = 5.123456),
        buddies = listOf("Ola Nordmann", "Kari"),
        tags = listOf("wreck", "deep dive"),
        gasMixes = listOf(GasMix(o2Permille = 180, hePermille = 450), GasMix(o2Permille = 500, hePermille = 0)),
        tanks = listOf(
            TankEntry(0, volumeMl = 24_000, workingPressureMbar = 232_000, startPressureMbar = 220_000, endPressureMbar = 60_000, o2Permille = 180, hePermille = 450),
            TankEntry(1, volumeMl = 7_000, workingPressureMbar = 200_000, startPressureMbar = 200_000, endPressureMbar = 120_000, o2Permille = 500, hePermille = 0),
        ),
        computers = listOf(primary, secondary),
    )

    /** No UTC offset, a site with only a name, one tank and a plain profile. */
    val plain = DiveEntry(
        number = 43,
        startEpochSeconds = 1_717_236_000,
        durationSeconds = 1_800,
        maxDepthMm = 12_340,
        meanDepthMm = 8_000,
        waterTempMk = 283_150,
        site = SiteRef("Shore"),
        gasMixes = listOf(GasMix(o2Permille = 320, hePermille = 0)),
        tanks = listOf(TankEntry(0, volumeMl = 12_000, workingPressureMbar = 232_000, startPressureMbar = 200_000, endPressureMbar = 50_000, o2Permille = 320, hePermille = 0)),
        computers = listOf(
            ComputerEntry(
                model = "Suunto Vyper",
                maxDepthMm = 12_340,
                meanDepthMm = 8_000,
                waterTempMk = 283_150,
                samples = listOf(
                    Sample(timeOffsetSeconds = 0, depthMm = 0, temperatureMk = 285_150),
                    Sample(timeOffsetSeconds = 60, depthMm = 12_340, temperatureMk = 283_150),
                    Sample(timeOffsetSeconds = 120, depthMm = 0, temperatureMk = 283_150),
                ),
            ),
        ),
    )

    /** Logged by hand: a summary, no computer. */
    val manual = DiveEntry(
        number = 44,
        startEpochSeconds = 1_717_335_000,
        durationSeconds = 2_400,
        maxDepthMm = 18_000,
        meanDepthMm = 10_000,
        waterTempMk = 284_150,
        airTempMk = 293_150,
        notes = "Manual",
    )

    val log = DiveLog(listOf(rich, plain, manual))
}
