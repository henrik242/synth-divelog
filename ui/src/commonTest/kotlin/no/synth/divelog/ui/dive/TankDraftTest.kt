package no.synth.divelog.ui.dive

import no.synth.divelog.core.model.Tank
import no.synth.divelog.core.model.units.UnitSystem
import kotlin.test.Test
import kotlin.test.assertEquals

class TankDraftTest {
    private val tank = Tank(id = 1, diveId = 1, index = 0, volumeMl = 12_000, workingPressureMbar = 232_000)

    @Test
    fun carriesWorkingPressure() {
        assertEquals(232_000, TankDraft.of(tank, null, null, UnitSystem.METRIC).workingPressureMbar)
    }

    @Test
    fun sizeFollowsTheUnitSystem() {
        val metric = TankDraft.of(tank, null, null, UnitSystem.METRIC)
        assertEquals("12", metric.size)
        assertEquals("L", metric.sizeUnit(UnitSystem.METRIC))
        assertEquals(12_000, metric.volumeMl(UnitSystem.METRIC))

        // 12 L at 232 bar is 97 cuft of gas; typing a size converts back through the working pressure.
        val imperial = TankDraft.of(tank, null, null, UnitSystem.IMPERIAL)
        assertEquals("97", imperial.size)
        assertEquals("cuft", imperial.sizeUnit(UnitSystem.IMPERIAL))
        assertEquals(9_894, imperial.copy(size = "80").volumeMl(UnitSystem.IMPERIAL))
    }

    @Test
    fun untouchedFieldsKeepTheirStoredValues() {
        val draft = TankDraft.of(tank.copy(startPressureMbar = 200_000), null, null, UnitSystem.IMPERIAL)
        assertEquals("2901", draft.start)
        assertEquals(12_000, draft.volumeMl(UnitSystem.IMPERIAL))
        assertEquals(200_000, draft.startMbar(UnitSystem.IMPERIAL))
        assertEquals(200_017, draft.copy(start = "2901.0").startMbar(UnitSystem.IMPERIAL))
    }

    @Test
    fun imperialSizeWithoutWorkingPressureIsLitres() {
        val draft = TankDraft.of(tank.copy(workingPressureMbar = null), null, null, UnitSystem.IMPERIAL)
        assertEquals("12", draft.size)
        assertEquals("L", draft.sizeUnit(UnitSystem.IMPERIAL))
        assertEquals(11_000, draft.copy(size = "11").volumeMl(UnitSystem.IMPERIAL))
    }
}
