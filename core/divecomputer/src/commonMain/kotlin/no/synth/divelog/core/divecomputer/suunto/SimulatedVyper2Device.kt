package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException

/**
 * A [Transport] that answers the Vyper2-family ReadMemory and GetVersion commands
 * from an in-memory image, validating framing and CRC the way a real device would.
 * Drives the protocol end to end without a serial line; the line echo is the
 * transport's business, so none is modelled. Used by the tests and, with
 * [SimulatedVyper2.demoMemory], as the app's simulated dive computer.
 */
class SimulatedVyper2Device(
    private val memory: ByteArray,
    private val version: ByteArray = byteArrayOf(0x15, 0x00, 0x01, 0x04),
) : Transport {
    /** Number of ReadMemory commands answered. */
    var memoryReads = 0
        private set

    private var out = ByteArray(0)
    private var outPos = 0

    override fun open() {}

    override fun write(data: ByteArray) {
        require(data.isNotEmpty()) { "empty command" }
        require(SuuntoCrc.xor(data, 0, data.size - 1) == data[data.size - 1]) { "bad command CRC" }
        when (data[0]) {
            0x05.toByte() -> {
                require(data.size == 7) { "expected a 7-byte read command, got ${data.size}" }
                val address = ((data[3].toInt() and 0xFF) shl 8) or (data[4].toInt() and 0xFF)
                val count = data[5].toInt() and 0xFF
                memoryReads++
                val reply = byteArrayOf(0x05, 0x00, (3 + count).toByte(), data[3], data[4], data[5]) +
                    memory.copyOfRange(address, address + count)
                out = SuuntoCrc.appended(reply)
            }
            0x0F.toByte() -> {
                out = SuuntoCrc.appended(byteArrayOf(0x0F, 0x00, 0x04) + version)
            }
            else -> throw IllegalArgumentException("unexpected command 0x${(data[0].toInt() and 0xFF).toString(16)}")
        }
        outPos = 0
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        if (outPos >= out.size) throw TransportTimeoutException()
        val n = minOf(length, out.size - outPos)
        out.copyInto(buffer, offset, outPos, outPos + n)
        outPos += n
        return n
    }

    override fun close() {}
}

/** HelO2 memory images in the real on-device layout, for [SimulatedVyper2Device]. */
object SimulatedVyper2 {
    const val MEM_SIZE = 0x8000

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

    /** [dives] made-up 45-minute nitrox dives on consecutive days, a little deeper each day. */
    fun demoMemory(dives: Int = 12): ByteArray =
        memory((0 until dives).map { demoRecord(day = 1 + it, maxDepthCm = 1200 + 150 * it) })

    // One depth sample a minute and no other parameters or events.
    private fun demoRecord(day: Int, maxDepthCm: Int): ByteArray {
        val depthsCm = intArrayOf(300, 900, maxDepthCm, maxDepthCm - 200, 800, 500, 300, 0)
        val d = ByteArray(0x100)
        u16le(d, 0x09, maxDepthCm)
        u16le(d, 0x0D, 45) // dive time, minutes
        d[0x17] = 9; d[0x18] = 15 // 09:15
        u16le(d, 0x1A, 2026)
        d[0x1C] = 8; d[0x1D] = day.toByte() // August 2026
        d[0x1E] = 60 // sample interval, seconds
        d[0x1F] = 4 // MIXED mode -> gas table is read
        d[0x54 + 1] = 32 // EAN32
        val config = 0x84
        d[config] = 1 // one parameter: depth every tick, divisor 100
        d[config + 2] = 0x64; d[config + 3] = 1; d[config + 4] = 0x18
        var o = config + 2 + 3
        d[o++] = 0x01 // event marker; a large next tick means no events
        u16le(d, o, 0); o += 2
        u16le(d, o, 9999); o += 2
        for (depth in depthsCm) { u16le(d, o, depth); o += 2 }
        return d.copyOfRange(0, o)
    }

    private fun wrap(address: Int): Int =
        if (address >= SuuntoVyper2Dump.RB_PROFILE_END) address - SuuntoVyper2Dump.ringSize else address

    private fun u16le(d: ByteArray, offset: Int, value: Int) {
        d[offset] = (value and 0xFF).toByte()
        d[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }
}
