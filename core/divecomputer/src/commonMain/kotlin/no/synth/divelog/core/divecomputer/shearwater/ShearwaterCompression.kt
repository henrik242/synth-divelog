package no.synth.divelog.core.divecomputer.shearwater

/**
 * The compression used for Petrel dive downloads: a 9-bit run-length stage over
 * a big-endian bit stream, then a block XOR stage. Predator downloads are
 * uncompressed and do not use this.
 */
object ShearwaterCompression {
    private const val XOR_BLOCK = 32

    /** Result of a run-length pass; [complete] is true once the end marker is seen. */
    class LreResult(val data: ByteArray, val complete: Boolean)

    /**
     * Decode the 9-bit run-length stream. Each 9-bit code, read MSB-first: bit 8
     * set is a literal byte (low 8 bits); bit 8 clear and non-zero is a run of
     * that many zero bytes; zero is the end-of-stream marker.
     */
    fun decompressLre(input: ByteArray): LreResult {
        val out = ArrayList<Byte>(input.size * 2)
        val totalBits = input.size * 8
        var offset = 0
        var complete = false
        while (offset + 9 <= totalBits) {
            val byteIndex = offset / 8
            val bitIndex = offset % 8
            val hi = input[byteIndex].toInt() and 0xFF
            val lo = if (byteIndex + 1 < input.size) input[byteIndex + 1].toInt() and 0xFF else 0
            val window = (hi shl 8) or lo
            val value = (window shr (7 - bitIndex)) and 0x1FF
            offset += 9
            when {
                value == 0 -> { complete = true; break }
                value and 0x100 != 0 -> out.add((value and 0xFF).toByte())
                else -> repeat(value) { out.add(0) }
            }
        }
        return LreResult(out.toByteArray(), complete)
    }

    /** Undo the block XOR stage in place: each byte is XORed with the one a block back. */
    fun decompressXor(data: ByteArray) {
        for (i in XOR_BLOCK until data.size) {
            data[i] = (data[i].toInt() xor data[i - XOR_BLOCK].toInt()).toByte()
        }
    }
}
