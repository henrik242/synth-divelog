package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.transport.TransportException

/** A dive the fake serves: its manifest [fingerprint] and [address], and its decompressed [data]. */
internal class FakeDive(val fingerprint: String, val address: Long, val data: ByteArray)

/**
 * The command side of an in-memory Petrel-family computer: [answer] takes a request
 * payload (after the 4-byte header) and returns the reply payload, or null for a command
 * that gets none (the shutdown). It serves identifiers, the manifest pages in turn (then
 * terminators) and each dive run-length compressed. A transport adapter (byte stream or
 * BLE packets) wraps it. [dropAfterDives] fails the init of every dive read after that
 * many, like a link that drops.
 */
internal class FakeShearwaterDevice(
    private val dives: List<FakeDive>,
    private val identifiers: Map<Int, ByteArray> = emptyMap(),
    private val dropAfterDives: Int = Int.MAX_VALUE,
    manifestPages: List<ByteArray> = listOf(manifestPage(dives)),
) {
    /** Full addresses of the dive reads, in order. */
    val diveReads = mutableListOf<Long>()
    val commands = mutableListOf<ByteArray>()
    private val pages = ArrayDeque(manifestPages)
    private var region = ByteArray(0)
    private var regionPos = 0

    fun answer(payload: ByteArray): ByteArray? {
        commands += payload
        return when (payload[0].toInt() and 0xFF) {
            0x22 -> {
                val id = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
                val data = identifiers[id] ?: error("identifier ${id.toString(16)}")
                byteArrayOf(0x62, payload[1], payload[2]) + data
            }
            0x35 -> {
                val address = (0 until 4).fold(0L) { v, i -> (v shl 8) or (payload[3 + i].toLong() and 0xFF) }
                region = if (address == MANIFEST) {
                    pages.removeFirstOrNull() ?: ByteArray(PAGE_SIZE) { 0xFF.toByte() }
                } else {
                    if (diveReads.size >= dropAfterDives) throw TransportException("link dropped")
                    diveReads += address
                    // The base (0xC0000000 or 0x80000000) is the protocol's choice; tests check it in diveReads.
                    literalLre(dives.first { it.address == address and 0x0FFFFFFFL }.data)
                }
                regionPos = 0
                byteArrayOf(0x75, 0x10, 0x80.toByte())
            }
            0x36 -> {
                val n = minOf(0x80, region.size - regionPos)
                val block = region.copyOfRange(regionPos, regionPos + n)
                regionPos += n
                byteArrayOf(0x76, payload[1]) + block
            }
            0x37 -> byteArrayOf(0x77, 0x00)
            0x2E -> null // shutdown: no reply
            else -> error("command ${payload[0]}")
        }
    }

    companion object {
        const val MANIFEST = 0xE0000000L
        const val PAGE_SIZE = 32 * 48

        /** One manifest page: [deleted] deleted records, a record per dive newest first, then terminators. */
        fun manifestPage(dives: List<FakeDive>, deleted: Int = 0): ByteArray {
            val page = ByteArray(PAGE_SIZE) { 0xFF.toByte() }
            for (i in 0 until deleted) {
                page[i * 32] = 0x5A
                page[i * 32 + 1] = 0x23
            }
            dives.forEachIndexed { i, dive ->
                val r = (deleted + i) * 32
                page[r] = 0xA5.toByte()
                page[r + 1] = 0xC4.toByte()
                for (k in 0 until 4) page[r + 4 + k] = dive.fingerprint.substring(k * 2, k * 2 + 2).toInt(16).toByte()
                for (k in 0 until 4) page[r + 20 + k] = (dive.address ushr (8 * (3 - k))).toByte()
            }
            return page
        }

        /** A reply frame (before SLIP): the response header, then [body]. */
        fun replyFrame(body: ByteArray): ByteArray = byteArrayOf(0x01, 0xFF.toByte(), (body.size + 1).toByte(), 0x00) + body

        /** Literal-only 9-bit run-length stream with the end code. Blobs up to 32 bytes skip the XOR stage. */
        fun literalLre(data: ByteArray): ByteArray {
            val codes = data.map { 0x100 or (it.toInt() and 0xFF) } + 0
            val bits = codes.flatMap { code -> (8 downTo 0).map { (code ushr it) and 1 } }
            return ByteArray((bits.size + 7) / 8) { byte ->
                (0 until 8).fold(0) { v, bit -> (v shl 1) or (bits.getOrNull(byte * 8 + bit) ?: 0) }.toByte()
            }
        }
    }
}
