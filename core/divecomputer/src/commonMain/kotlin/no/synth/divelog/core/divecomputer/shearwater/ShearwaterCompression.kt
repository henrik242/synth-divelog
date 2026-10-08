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
        val decoder = LreDecoder()
        decoder.feed(input)
        return LreResult(decoder.output(), decoder.complete)
    }

    /**
     * Incremental [decompressLre] for a stream that arrives in blocks: each [feed]
     * decodes only the codes the new bytes complete.
     */
    class LreDecoder {
        private var input = ByteArray(1_024)
        private var out = ByteArray(4_096)
        private var outSize = 0
        private var bitOffset = 0

        /** Bytes fed so far. */
        var inputSize = 0
            private set

        /** True once the end marker is seen; later input is ignored. */
        var complete = false
            private set

        fun feed(bytes: ByteArray): Boolean {
            if (complete) return true
            if (inputSize + bytes.size > input.size) input = input.copyOf(maxOf(input.size * 2, inputSize + bytes.size))
            bytes.copyInto(input, inputSize)
            inputSize += bytes.size
            val totalBits = inputSize * 8
            while (bitOffset + 9 <= totalBits) {
                val byteIndex = bitOffset / 8
                val hi = input[byteIndex].toInt() and 0xFF
                val lo = if (byteIndex + 1 < inputSize) input[byteIndex + 1].toInt() and 0xFF else 0
                val value = (((hi shl 8) or lo) shr (7 - bitOffset % 8)) and 0x1FF
                bitOffset += 9
                when {
                    value == 0 -> { complete = true; break }
                    value and 0x100 != 0 -> append((value and 0xFF).toByte(), 1)
                    else -> append(0, value)
                }
            }
            return complete
        }

        fun output(): ByteArray = out.copyOf(outSize)

        private fun append(b: Byte, count: Int) {
            if (outSize + count > out.size) out = out.copyOf(maxOf(out.size * 2, outSize + count))
            out.fill(b, outSize, outSize + count)
            outSize += count
        }
    }

    /** Undo the block XOR stage in place: each byte is XORed with the one a block back. */
    fun decompressXor(data: ByteArray) {
        for (i in XOR_BLOCK until data.size) {
            data[i] = (data[i].toInt() xor data[i - XOR_BLOCK].toInt()).toByte()
        }
    }
}
