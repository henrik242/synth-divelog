package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.fnv1a

/**
 * Splits the old-Vyper profile ring buffer into one raw blob per dive. Layout as in
 * libdivecomputer (suunto_vyper.c, suunto_common.c):
 *
 * - The profile lives in a ring buffer spanning [RING_BEGIN]..[RING_END).
 * - A big-endian 16-bit end-of-profile pointer in the header ([POINTER_OFFSET])
 *   addresses the `0x82` end-of-profile marker written after the newest dive.
 * - Dives are stored oldest first, each in time order: a 14-byte header, the
 *   samples, a `0x80` end-of-dive marker and four trailer bytes. A dive therefore
 *   starts where the byte [PEEK] positions back is the previous dive's `0x80`.
 *
 * Walking back from the pointer yields the dives newest first.
 */
object SuuntoVyperDump {
    const val FORMAT_ID = "suunto-vyper-log"

    const val RING_BEGIN = 0x71
    const val RING_END = 0x2000
    const val POINTER_OFFSET = 0x51
    const val MODEL_OFFSET = 0x24

    /** Model, firmware and a four-byte serial, from [MODEL_OFFSET]. */
    const val DEVINFO_SIZE = 6

    const val END_OF_DIVE = 0x80
    const val END_OF_DATA = 0x82

    /** Distance from a dive's first byte back to the previous dive's `0x80`. */
    private const val PEEK = 5

    /**
     * Extract dives newest first from [ring], the raw bytes of [RING_BEGIN]..[RING_END).
     * [eopAddress] is the end-of-profile pointer from the header.
     */
    fun extract(ring: ByteArray, eopAddress: Int): List<RawDive> {
        val size = ring.size
        val eop = eopAddress - RING_BEGIN
        if (eop !in 0 until size || ring.u8(eop) != END_OF_DATA) return emptyList()

        fun back(i: Int, n: Int) = ((i - n) % size + size) % size

        val dives = mutableListOf<RawDive>()
        var current = eop
        var previous = eop
        repeat(size) {
            current = back(current, 1)
            if (ring.u8(current) == END_OF_DATA) return dives
            if (ring.u8(back(current, PEEK)) == END_OF_DIVE) {
                val length = back(previous, current).let { if (it == 0) size else it }
                val record = ByteArray(length) { ring[(current + it) % size] }
                dives += RawDive(fingerprint = fnv1a(record), data = record, formatId = FORMAT_ID)
                previous = current
            }
        }
        return dives
    }

    /** Model name for the type byte at [MODEL_OFFSET], or a hex fallback. */
    fun modelName(typeByte: Int): String = when (typeByte) {
        0x01 -> "Spyder"
        0x03 -> "Stinger"
        0x04 -> "Mosquito"
        0x05 -> "D3"
        0x0A -> "Vyper"
        0x0B -> "Vytec"
        0x0C -> "Cobra"
        0x0D -> "Gekko"
        0x16 -> "Zoop"
        else -> "Vyper-family (0x${typeByte.toString(16)})"
    }

    private fun ByteArray.u8(i: Int) = this[i].toInt() and 0xFF
}
