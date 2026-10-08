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
    const val MEM_SIZE = 0x8000

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

    /**
     * A full memory image holding [records] in the profile ring, oldest first, each
     * linked to its neighbours, with the directory header filled in. [validBegin]
     * controls whether the header's begin pointer is sane (exact budget) or corrupt
     * (whole-ring fallback). [startAt] places the oldest record, so the records can be
     * laid across the ring wrap.
     */
    fun memory(
        records: List<ByteArray>,
        validBegin: Boolean = true,
        startAt: Int = SuuntoVyper2Dump.RB_PROFILE_BEGIN,
    ): ByteArray {
        val mem = ByteArray(MEM_SIZE)

        // Serial at 0x23: four bytes, two decimal digits each -> "01020304".
        mem[0x23] = 1; mem[0x24] = 2; mem[0x25] = 3; mem[0x26] = 4

        val begin = startAt
        val starts = IntArray(records.size)
        var addr = begin
        for (i in records.indices) {
            starts[i] = addr
            addr = wrap(addr + records[i].size + 4) // record data plus the prev/next head
        }
        val end = addr // one past the newest record

        for (i in records.indices) {
            val start = starts[i]
            val prev = if (i == 0) begin else starts[i - 1]
            val next = if (i == records.size - 1) end else starts[i + 1]
            val head = ByteArray(4)
            u16le(head, 0, prev)
            u16le(head, 2, next)
            (head + records[i]).forEachIndexed { k, b -> mem[wrap(start + k)] = b }
        }

        // Header at 0x190: last, count, end, begin.
        val header = SuuntoVyper2Dump.HEADER_OFFSET
        u16le(mem, header + 0, starts.last())
        u16le(mem, header + 2, records.size)
        u16le(mem, header + 4, end)
        u16le(mem, header + 6, if (validBegin) begin else 0xFF02)
        return mem
    }

    private fun wrap(address: Int): Int =
        if (address >= SuuntoVyper2Dump.RB_PROFILE_END) address - SuuntoVyper2Dump.ringSize else address

    private fun u16le(d: ByteArray, offset: Int, value: Int) {
        d[offset] = (value and 0xFF).toByte()
        d[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
