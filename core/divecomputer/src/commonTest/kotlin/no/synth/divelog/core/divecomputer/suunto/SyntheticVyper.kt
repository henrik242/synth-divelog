package no.synth.divelog.core.divecomputer.suunto

/**
 * Builds a synthetic old-Vyper memory image in the layout of [SuuntoVyperDump] so
 * the protocol and parser can be exercised without hardware.
 */
object SyntheticVyper {
    const val MODEL_VYPER = 0x0A
    const val INTERVAL = 20

    /**
     * One nitrox dive, 2024-07-15 10:30, square profile in feet 0, 33, 33, 66, 66, 33,
     * 0 (deltas +33, 0, +33, 0, -33, -33) with a bookmark before the fourth sample.
     * Tank 200 -> 50 bar, surface 20 C, minimum 18 C.
     */
    fun record(o2: Int = 32, minute: Int = 30): ByteArray = bytes(
        0x00, 0x00, 0x00, INTERVAL, 0x00, 100, o2, 0x00, 20,
        24, 7, 15, 10, minute,
        33, 0, 33, 0x7C, 0, -33, -33,
        0x80, 18, 0x00, 25, 0x00,
    )

    class Image(val ring: ByteArray, val pointer: Int, val memory: ByteArray)

    /** [records] oldest first, written from ring offset [startAt] (wrapping), then `0x82`. */
    fun image(records: List<ByteArray>, startAt: Int = 0): Image {
        val ringLen = SuuntoVyperDump.RING_END - SuuntoVyperDump.RING_BEGIN
        val ring = ByteArray(ringLen)
        // The trailer of an older, overwritten dive, so the oldest record has its 0x80 to peek at.
        val stream = bytes(0x80, 0, 0, 0, 0) + records.reduce(ByteArray::plus) + bytes(0x82)
        stream.forEachIndexed { i, b -> ring[(startAt + i) % ringLen] = b }
        val pointer = SuuntoVyperDump.RING_BEGIN + (startAt + stream.size - 1) % ringLen

        val memory = ByteArray(SuuntoVyperDump.RING_END)
        bytes(MODEL_VYPER, 0x11, 0, 1, 23, 45).copyInto(memory, SuuntoVyperDump.MODEL_OFFSET)
        memory[SuuntoVyperDump.POINTER_OFFSET] = ((pointer ushr 8) and 0xFF).toByte()
        memory[SuuntoVyperDump.POINTER_OFFSET + 1] = (pointer and 0xFF).toByte()
        ring.copyInto(memory, SuuntoVyperDump.RING_BEGIN)
        return Image(ring, pointer, memory)
    }

    fun squareDiveImage(): Image = image(listOf(record()))

    private fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
}
