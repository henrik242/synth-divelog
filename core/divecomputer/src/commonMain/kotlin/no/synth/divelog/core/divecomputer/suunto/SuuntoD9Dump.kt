package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.RawDive

/**
 * Walks the newer Suunto D9 family profile ring and splits it into one raw blob
 * per dive, newest first.
 *
 * Memory facts for this family (HelO2, Vyper Air, Vyper2, Cobra2/3, D-series):
 *
 * - The profile lives in a ring buffer spanning [RB_PROFILE_BEGIN]..[RB_PROFILE_END).
 *   An eight byte directory header at [HEADER_OFFSET] holds four little-endian 16-bit
 *   pointers: `last` (start of the newest dive), `count` (number of dives), `end`
 *   (one past the newest dive) and `begin` (start of the oldest dive).
 * - Each dive record begins with two little-endian pointers: `prev` (start of the
 *   older neighbour) and `next` (start of the newer neighbour, or `end` for the
 *   newest). The rest of the record is the dive data the parser reads.
 * - The chain is followed backward from `last`: the size of a dive is the ring
 *   distance from its own start up to the next (newer) start. Each hop validates
 *   that the pointers stay in range and that the chain is continuous.
 * - When `begin` is out of range the header is partly corrupt, so the walk falls
 *   back to scanning the whole ring and stops on the first record whose pointers no
 *   longer line up (the unwritten gap past the newest dive).
 *
 * The record data handed to the parser excludes the four byte prev/next head, so
 * parser offsets are relative to the first real data byte.
 */
object SuuntoD9Dump {
    const val FORMAT_ID = "suunto-d9-log"

    const val HEADER_OFFSET = 0x0190
    const val HEADER_SIZE = 8
    const val FINGERPRINT_OFFSET = 0x0011
    const val SERIAL_OFFSET = 0x0023
    const val SERIAL_SIZE = 4
    const val RB_PROFILE_BEGIN = 0x019A
    const val RB_PROFILE_END = 0x7FFE

    /** Four byte prev/next head in front of each dive record. */
    private const val RECORD_HEAD = 4

    private val ringSize get() = RB_PROFILE_END - RB_PROFILE_BEGIN

    /**
     * Extract dives newest-first from [ring], the raw bytes of
     * [RB_PROFILE_BEGIN]..[RB_PROFILE_END), using the eight byte directory [header]
     * read from [HEADER_OFFSET].
     */
    fun extract(ring: ByteArray, header: ByteArray): List<RawDive> {
        require(ring.size == ringSize) { "ring must be $ringSize bytes, got ${ring.size}" }
        require(header.size >= HEADER_SIZE) { "header must be at least $HEADER_SIZE bytes" }

        val last = u16le(header, 0)
        val count = u16le(header, 2)
        val end = u16le(header, 4)
        val begin = u16le(header, 6)

        if (!inRing(last) || !inRing(end)) return emptyList()

        // Bytes still to account for. A valid begin gives an exact budget; a corrupt
        // one forces a whole-ring scan that terminates on the first broken record.
        var remaining = if (inRing(begin)) ringDistance(begin, end, full = count > 0) else ringSize

        val dives = ArrayList<RawDive>()
        var current = last
        var previous = end
        while (remaining > 0) {
            val size = ringDistance(current, previous, full = true)
            if (size < RECORD_HEAD || size > remaining) break
            remaining -= size

            val record = recordAt(current, size, ring)
            val prev = u16le(record, 0)
            val next = u16le(record, 2)
            if (!inRing(prev) || !inRing(next)) break
            if (next != previous && next != current) break

            // next == current marks an incomplete dive, which is skipped.
            if (next != current) {
                val data = record.copyOfRange(RECORD_HEAD, record.size)
                dives += RawDive(fingerprint(data), data, FORMAT_ID)
            }

            previous = current
            current = prev
        }
        return dives
    }

    /** Device serial as decimal digits, two per byte (e.g. 0x5E -> "94"). */
    fun decodeSerial(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString().padStart(2, '0') }

    /** Model name for the version id byte, or a hex fallback. */
    fun modelName(modelByte: Int): String = when (modelByte) {
        0x0E -> "D9"
        0x0F -> "D6"
        0x10 -> "Vyper2"
        0x11 -> "Cobra2"
        0x12 -> "D4"
        0x13 -> "Vyper Air"
        0x14 -> "Cobra3"
        0x15 -> "HelO2"
        0x19 -> "D4i"
        0x1A -> "D6i"
        0x1B -> "D9tx"
        0x1C -> "DX"
        else -> "D9-family (0x${modelByte.toString(16)})"
    }

    private fun inRing(address: Int): Boolean = address in RB_PROFILE_BEGIN until RB_PROFILE_END

    /**
     * Forward distance in the ring from [a] to [b]. When [a] == [b] the result is
     * the whole ring if [full], otherwise zero.
     */
    private fun ringDistance(a: Int, b: Int, full: Boolean): Int {
        val n = ringSize
        val raw = if (a > b) {
            val k = (a - b) % n
            if (k == 0) 0 else n - k
        } else {
            (b - a) % n
        }
        return if (raw == 0) (if (full) n else 0) else raw
    }

    /** Copy [size] bytes starting at ring address [start], wrapping at the ring end. */
    private fun recordAt(start: Int, size: Int, ring: ByteArray): ByteArray {
        val out = ByteArray(size)
        var idx = start - RB_PROFILE_BEGIN
        for (i in 0 until size) {
            out[i] = ring[idx]
            idx++
            if (idx >= ring.size) idx = 0
        }
        return out
    }

    private fun u16le(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    /** Stable per-dive id from the record bytes (FNV-1a), for duplicate detection. */
    private fun fingerprint(record: ByteArray): String {
        var hash = 0x811C9DC5u
        for (b in record) {
            hash = hash xor (b.toUInt() and 0xFFu)
            hash *= 0x01000193u
        }
        return hash.toString(16).padStart(8, '0')
    }
}
