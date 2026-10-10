package no.synth.divelog.core.divecomputer.suunto

/**
 * Builds a small synthetic Vyper2 (HelO2) memory image in the real on-device layout, so
 * the directory walk and parser can be tested without any captured dive data.
 *
 * A dive record is laid out exactly as the parser reads it: fixed summary fields, an
 * eight-entry gas-mix table, a two-parameter sample configuration (depth every tick,
 * temperature every other tick), then a flat profile with one mid-dive gas switch.
 */
object SyntheticVyper2 {

    // Values baked into the canonical record, for the parser test to assert against.
    const val YEAR = 2023
    const val MONTH = 7
    const val DAY = 15
    const val HOUR = 8
    const val MINUTE = 30
    const val SECOND = 45
    const val INTERVAL = 10
    const val DIVETIME_MIN = 1

    /** Depths in centimetres, one per tick. */
    val DEPTHS_CM = intArrayOf(100, 300, 600, 400, 50)

    /** Temperatures in Celsius, one per even tick (ticks 0, 2, 4). */
    val TEMPS_C = intArrayOf(20, 18, 19)

    /** Tick at which the gas switch to mix index 1 happens (0-based). */
    const val GAS_SWITCH_TICK = 2

    const val GAS0_O2 = 32
    const val GAS1_O2 = 21

    /** Parser-visible data for one HelO2 dive. [maxDepthCm] sets the summary max-depth field. */
    fun helo2Record(maxDepthCm: Int = 600): ByteArray {
        val d = ByteArray(0xA8)

        u16le(d, 0x09, maxDepthCm)
        u16le(d, 0x0D, DIVETIME_MIN)

        // Date/time at 0x17: hour, minute, second, year(le), month, day.
        d[0x17] = HOUR.toByte()
        d[0x18] = MINUTE.toByte()
        d[0x19] = SECOND.toByte()
        d[0x1A] = (YEAR and 0xFF).toByte()
        d[0x1B] = ((YEAR ushr 8) and 0xFF).toByte()
        d[0x1C] = MONTH.toByte()
        d[0x1D] = DAY.toByte()

        d[0x1E] = INTERVAL.toByte()
        d[0x1F] = 4 // MIXED mode -> gas table is read
        d[0x26] = 0 // initial mix index

        // Gas-mix table at 0x54: eight entries of six bytes, o2 at +1, he at +2.
        d[0x54 + 1] = GAS0_O2.toByte()
        d[0x54 + 6 + 1] = GAS1_O2.toByte()

        // Sample configuration at config = 0x84.
        val config = 0x84
        d[config] = 2 // two parameters
        // param0: depth (0x64), every tick, divisor 100 (divisor index 6 -> bits 0x18).
        d[config + 2] = 0x64
        d[config + 3] = 1
        d[config + 4] = 0x18
        // param1: temperature (0x74), every other tick, divisor 1 (index 0).
        d[config + 5] = 0x74
        d[config + 6] = 2
        d[config + 7] = 0x00

        val profile = config + 2 + 2 * 3 // 0x8C
        d[profile] = 0x01
        d[profile + 1] = 0x00
        d[profile + 2] = 0x00
        u16le(d, profile + 3, GAS_SWITCH_TICK + 1) // marker is 1-based

        var o = profile + 5
        var tempIdx = 0
        for (tick in DEPTHS_CM.indices) {
            u16le(d, o, DEPTHS_CM[tick]); o += 2
            if (tick % 2 == 0) {
                d[o] = TEMPS_C[tempIdx++].toByte(); o += 1
            }
            if (tick == GAS_SWITCH_TICK) {
                // Gas change (0x06): type with mix index 1 and the high bit set, he, o2, seconds.
                d[o++] = 0x06
                d[o++] = 0x81.toByte()
                d[o++] = 0 // helium
                d[o++] = GAS1_O2.toByte()
                d[o++] = 0 // seconds within tick
                // Next event marker (0x01): current, next. A large next ends the events.
                d[o++] = 0x01
                u16le(d, o, GAS_SWITCH_TICK + 1); o += 2
                u16le(d, o, 9999); o += 2
            }
        }
        return d.copyOfRange(0, o)
    }

    /** A full memory image holding [records]; see [SimulatedVyper2.memory]. */
    fun memory(
        records: List<ByteArray>,
        validBegin: Boolean = true,
        startAt: Int = SuuntoVyper2Dump.RB_PROFILE_BEGIN,
    ): ByteArray = SimulatedVyper2.memory(records, validBegin, startAt)

    private fun u16le(d: ByteArray, offset: Int, value: Int) {
        d[offset] = (value and 0xFF).toByte()
        d[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
