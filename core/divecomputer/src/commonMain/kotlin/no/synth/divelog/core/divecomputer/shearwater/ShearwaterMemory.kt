package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DownloadCancelledException
import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException

/**
 * Reads a span of device memory using the upload command set: an init that
 * announces the address and length, a run of block reads, then an exit. Returns
 * the raw bytes; the Predator dump is uncompressed.
 */
class ShearwaterMemory(private val link: ShearwaterLink) {
    fun read(
        baseAddress: Long,
        size: Int,
        onProgress: (read: Int, total: Int) -> Unit = { _, _ -> },
        cancel: CancellationSignal = CancellationSignal.NONE,
    ): ByteArray {
        begin(baseAddress, size)
        try {
            val out = ByteArray(size)
            var read = 0
            // Block sequence counter starts at 1 (diagnostic TransferData
            // convention), increments per block and wraps through the byte range.
            var block = 1
            while (read < size) {
                if (cancel.isCancelled()) throw DownloadCancelledException()
                val data = readBlock(block)
                if (data.isEmpty()) throw ProtocolException("Empty block $block at offset $read")
                val n = minOf(data.size, size - read)
                data.copyInto(out, read, 0, n)
                read += n
                block = (block + 1) and 0xFF
                onProgress(read, size)
            }
            return out
        } finally {
            // Always close the transfer so the device is not left mid-session.
            runCatching { finish() }
        }
    }

    /**
     * Read a compressed region of unknown length (Petrel dive). The compressed
     * stream signals its own end, so blocks are pulled until the run-length stage
     * reports completion, then the block XOR stage is undone.
     */
    fun readCompressed(
        baseAddress: Long,
        onProgress: (read: Int) -> Unit = {},
        cancel: CancellationSignal = CancellationSignal.NONE,
    ): ByteArray {
        begin(baseAddress, MAX_COMPRESSED_SIZE, compressed = true)
        try {
            val raw = ArrayList<Byte>()
            var block = 1
            while (true) {
                if (cancel.isCancelled()) throw DownloadCancelledException()
                val data = readBlock(block)
                if (data.isEmpty()) throw ProtocolException("Empty compressed block $block")
                for (b in data) raw.add(b)
                val lre = ShearwaterCompression.decompressLre(raw.toByteArray())
                if (lre.complete) {
                    val result = lre.data
                    ShearwaterCompression.decompressXor(result)
                    return result
                }
                block = (block + 1) and 0xFF
                onProgress(raw.size)
                if (raw.size > MAX_COMPRESSED_SIZE) throw ProtocolException("Compressed dive exceeds limit")
            }
        } finally {
            runCatching { finish() }
        }
    }

    /** Send the init command; returns the maximum block payload length. */
    private fun begin(baseAddress: Long, size: Int, compressed: Boolean = false): Int {
        val payload = byteArrayOf(
            CMD_INIT,
            if (compressed) COMPRESSION_ON else COMPRESSION_OFF,
            INIT_TAG,
            ((baseAddress ushr 24) and 0xFF).toByte(),
            ((baseAddress ushr 16) and 0xFF).toByte(),
            ((baseAddress ushr 8) and 0xFF).toByte(),
            (baseAddress and 0xFF).toByte(),
            ((size ushr 16) and 0xFF).toByte(),
            ((size ushr 8) and 0xFF).toByte(),
            (size and 0xFF).toByte(),
        )
        // The first init after connecting is sometimes dropped by the device's
        // serial stack; retry a few times before giving up.
        var lastError: Exception? = null
        repeat(INIT_RETRIES) {
            try {
                val response = link.exchange(payload)
                if (response.isEmpty() || response[0] != RSP_INIT) {
                    throw ProtocolException("Bad init response")
                }
                return parseMaxLen(response)
            } catch (e: TransportTimeoutException) {
                lastError = e
            }
        }
        throw lastError ?: ProtocolException("No init response")
    }

    private fun parseMaxLen(response: ByteArray): Int {
        if (response.size < 2) return DEFAULT_MAX_LEN
        val count = (response[1].toInt() and 0xF0) ushr 4
        if (count !in 1..4 || response.size < 2 + count) return DEFAULT_MAX_LEN
        var value = 0
        for (i in 0 until count) {
            value = (value shl 8) or (response[2 + i].toInt() and 0xFF)
        }
        return if (value in 1..DEFAULT_MAX_LEN) value else DEFAULT_MAX_LEN
    }

    private fun readBlock(block: Int): ByteArray {
        // V1 transfer-block request is two bytes: command + counter. (V2 appends
        // a padding byte; a strict Petrel rejects the extra byte.)
        val response = link.exchange(byteArrayOf(CMD_DATA, block.toByte()))
        if (response.size < 2 || response[0] != RSP_DATA) {
            throw ProtocolException("Bad block response for block $block")
        }
        val echoed = response[1].toInt() and 0xFF
        if (echoed != block) {
            throw ProtocolException("Block number mismatch: asked $block got $echoed")
        }
        return response.copyOfRange(2, response.size)
    }

    private fun finish() {
        val response = link.exchange(byteArrayOf(CMD_EXIT))
        if (response.isEmpty() || response[0] != RSP_EXIT) {
            throw ProtocolException("Bad exit response")
        }
    }

    companion object {
        private const val CMD_INIT = 0x35.toByte()
        private const val CMD_DATA = 0x36.toByte()
        private const val CMD_EXIT = 0x37.toByte()
        private const val RSP_INIT = 0x75.toByte()
        private const val RSP_DATA = 0x76.toByte()
        private const val RSP_EXIT = 0x77.toByte()
        private const val COMPRESSION_OFF = 0x00.toByte()
        private const val COMPRESSION_ON = 0x10.toByte()
        private const val INIT_TAG = 0x34.toByte()
        private const val DEFAULT_MAX_LEN = 254
        private const val MAX_COMPRESSED_SIZE = 0xFFFFFF
        private const val INIT_RETRIES = 4
    }
}
