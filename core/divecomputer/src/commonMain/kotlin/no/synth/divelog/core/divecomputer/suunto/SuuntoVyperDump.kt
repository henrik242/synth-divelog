package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.fnv1a

/**
 * Splits the old-Vyper profile ring buffer into one raw blob per dive.
 *
 * Memory facts for this family (Zoop, Vyper, Vytec, Cobra, Gekko, Stinger,
 * Mosquito):
 *
 * - The profile lives in a ring buffer spanning [RING_BEGIN]..[RING_END); when the
 *   write position reaches [RING_END] it wraps back to [RING_BEGIN].
 * - A big-endian 16-bit write pointer in the header ([POINTER_OFFSET]) addresses
 *   the last byte written, which is the `0x82` end-of-dataset marker written after
 *   the most recent dive.
 * - Each dive is stored oldest-sample-last: a three byte head (`OLF`, end pressure
 *   in bar/2, end temperature in Celsius) followed by one signed delta-depth byte
 *   (feet) per sample interval, and is terminated by a `0x80` end-of-dive marker.
 * - Unwritten ring bytes read as `0x00`.
 *
 * The exact head layout and markers are taken from the family protocol notes and
 * are not yet confirmed against a captured dump; see docs/protocol/suunto-zoop.md.
 */
object SuuntoVyperDump {
    const val FORMAT_ID = "suunto-vyper-log"

    const val RING_BEGIN = 0x71
    const val RING_END = 0x2000
    const val POINTER_OFFSET = 0x51
    const val MODEL_OFFSET = 0x24

    const val END_OF_DIVE = 0x80
    const val END_OF_DATA = 0x82

    /** Smallest blob we treat as a real dive (head plus at least one sample). */
    private const val MIN_RECORD = 4

    /**
     * Extract dives newest-first from [ring], the raw bytes of [RING_BEGIN]..[RING_END).
     * [eopAddress] is the write pointer from the header (absolute memory address of
     * the `0x82` marker).
     */
    fun extract(ring: ByteArray, eopAddress: Int): List<RawDive> {
        if (ring.isEmpty()) return emptyList()
        val size = ring.size
        val eopIndex = eopAddress - RING_BEGIN
        if (eopIndex !in 0 until size) return emptyList()

        // Linearise the ring into chronological order ending at the write pointer:
        // the byte just past the pointer is the oldest, the pointer itself the newest.
        val stream = ByteArray(size) { ring[(eopIndex + 1 + it) % size] }

        // Drop the trailing end-of-dataset marker, then any run of unwritten bytes
        // that pads the oldest end of a not-yet-full ring.
        var end = stream.size
        while (end > 0 && (stream[end - 1].toInt() and 0xFF) == END_OF_DATA) end--
        var start = 0
        while (start < end && stream[start].toInt() == 0) start++

        // Split on the end-of-dive marker. Records come out oldest-first.
        val records = mutableListOf<ByteArray>()
        var recordStart = start
        var i = start
        while (i < end) {
            if ((stream[i].toInt() and 0xFF) == END_OF_DIVE) {
                if (i > recordStart) records += stream.copyOfRange(recordStart, i)
                recordStart = i + 1
            }
            i++
        }
        if (end > recordStart) records += stream.copyOfRange(recordStart, end)

        // Newest dive is last in storage order.
        return records.asReversed()
            .filter { it.size >= MIN_RECORD && it.any { b -> b.toInt() != 0 } }
            .map { record ->
                RawDive(
                    fingerprint = fnv1a(record),
                    data = record,
                    formatId = FORMAT_ID,
                )
            }
    }

    /** Model name for the type byte at [MODEL_OFFSET], or a hex fallback. */
    fun modelName(typeByte: Int): String = when (typeByte) {
        0x03 -> "Stinger"
        0x04 -> "Mosquito"
        0x0A -> "Vytec"
        0x0B -> "Gekko"
        0x0C -> "Vyper"
        0x0D -> "Cobra"
        0x16 -> "Zoop"
        else -> "Vyper-family (0x${typeByte.toString(16)})"
    }
}
