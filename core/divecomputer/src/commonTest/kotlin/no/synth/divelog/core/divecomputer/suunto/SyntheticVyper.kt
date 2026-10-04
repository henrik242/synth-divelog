package no.synth.divelog.core.divecomputer.suunto

/**
 * Builds a synthetic old-Vyper memory image from the documented layout so the
 * protocol and parser can be exercised without hardware. One square-profile dive
 * is placed at the start of the ring with the write pointer just past it.
 *
 * Forward-time depths (feet): 0, 33, 33, 66, 66, 33, 0. Recorded deltas are
 * `previous - current`, so forward they are -33, 0, -33, 0, 33, 33; stored
 * end-of-dive first they reverse to 33, 33, 0, -33, 0, -33.
 */
object SyntheticVyper {
    const val MODEL_ZOOP = 0x16

    /**
     * Head (OLF byte, end pressure = 5 -> 10 bar, end temp = 18 C) then reverse-time
     * delta bytes. The head's first byte is non-zero so it is not mistaken for the
     * unwritten ring gap, which reads as 0x00 (see [SuuntoVyperDump]).
     */
    val RECORD = byteArrayOf(
        0x32, 0x05, 0x12,
        0x21, 0x21, 0x00, 0xDF.toByte(), 0x00, 0xDF.toByte(),
    )

    class Image(val ring: ByteArray, val pointer: Int, val memory: ByteArray)

    fun squareDiveImage(): Image {
        val ringLen = SuuntoVyperDump.RING_END - SuuntoVyperDump.RING_BEGIN
        val ring = ByteArray(ringLen)
        RECORD.copyInto(ring, 0)
        ring[RECORD.size] = SuuntoVyperDump.END_OF_DIVE.toByte()
        ring[RECORD.size + 1] = SuuntoVyperDump.END_OF_DATA.toByte()
        val pointer = SuuntoVyperDump.RING_BEGIN + RECORD.size + 1

        val memory = ByteArray(SuuntoVyperDump.RING_END)
        memory[SuuntoVyperDump.MODEL_OFFSET] = MODEL_ZOOP.toByte()
        memory[SuuntoVyperDump.POINTER_OFFSET] = ((pointer ushr 8) and 0xFF).toByte()
        memory[SuuntoVyperDump.POINTER_OFFSET + 1] = (pointer and 0xFF).toByte()
        ring.copyInto(memory, SuuntoVyperDump.RING_BEGIN)

        return Image(ring, pointer, memory)
    }
}
