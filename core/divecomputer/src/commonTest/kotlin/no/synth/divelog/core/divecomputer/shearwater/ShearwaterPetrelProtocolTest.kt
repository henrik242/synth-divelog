package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.transport.Slip
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Drives [ShearwaterPetrelProtocol] against an in-memory Petrel ([FakePetrel]) that serves
 * a manifest and run-length compressed dives over SLIP frames.
 */
class ShearwaterPetrelProtocolTest {
    private val dives = listOf(
        Triple("0000000a", 0x100L, ByteArray(20) { 10 }), // newest
        Triple("0000000b", 0x200L, ByteArray(20) { 11 }),
        Triple("0000000c", 0x300L, ByteArray(20) { 12 }), // oldest
    )

    @Test
    fun readsOldestFirstAndReturnsNewestFirst() {
        val device = FakePetrel(dives)
        val delivered = mutableListOf<Int>()
        val result = ShearwaterPetrelProtocol(device).download(
            knownFingerprint = null,
            listener = object : DownloadListener {
                override fun onDiveDownloaded(index: Int, dive: RawDive) { delivered += index }
            },
        )
        assertEquals(listOf(0x300L, 0x200L, 0x100L), device.diveReads)
        assertEquals(listOf(2, 1, 0), delivered)
        assertEquals(listOf("0000000a", "0000000b", "0000000c"), result.map { it.fingerprint })
        assertContentEquals(dives[0].third, result[0].data)
    }

    @Test
    fun stopsAtTheKnownDive() {
        val device = FakePetrel(dives)
        val result = ShearwaterPetrelProtocol(device).download(knownFingerprint = "0000000b")
        assertEquals(listOf("0000000a"), result.map { it.fingerprint })
        assertEquals(listOf(0x100L), device.diveReads)
    }

    @Test
    fun aDroppedLinkKeepsTheOlderDivesAlreadyRead() {
        val device = FakePetrel(dives, dropAfterDives = 1)
        val delivered = mutableListOf<String>()
        assertFailsWith<TransportException> {
            ShearwaterPetrelProtocol(device).download(
                knownFingerprint = null,
                listener = object : DownloadListener {
                    override fun onDiveDownloaded(index: Int, dive: RawDive) { delivered += dive.fingerprint }
                },
            )
        }
        // The oldest dive came through: it sits right after what is stored, so nothing is skipped.
        assertEquals(listOf("0000000c"), delivered)
    }
}

/** Answers the Petrel upload commands from a manifest and per-dive blobs. */
private class FakePetrel(
    private val dives: List<Triple<String, Long, ByteArray>>,
    private val dropAfterDives: Int = Int.MAX_VALUE,
) : Transport {
    val diveReads = mutableListOf<Long>()
    private var reply = ByteArray(0)
    private var replyPos = 0
    private var region = ByteArray(0)
    private var regionPos = 0

    override fun open() {}

    override fun close() {}

    override fun write(data: ByteArray) {
        val frame = Slip.unescape(data.copyOfRange(0, data.size - 1))
        val payload = frame.copyOfRange(4, frame.size)
        when (payload[0].toInt() and 0xFF) {
            0x35 -> {
                val address = (0 until 4).fold(0L) { v, i -> (v shl 8) or (payload[3 + i].toLong() and 0xFF) }
                region = if (address == MANIFEST) {
                    manifest()
                } else {
                    if (diveReads.size >= dropAfterDives) throw TransportException("link dropped")
                    val offset = address - DIVE_BASE
                    diveReads += offset
                    lre(dives.first { it.second == offset }.third)
                }
                regionPos = 0
                respond(0x75, 0x10, 0x80)
            }
            0x36 -> {
                val n = minOf(0x80, region.size - regionPos)
                val block = region.copyOfRange(regionPos, regionPos + n)
                regionPos += n
                respond(0x76, payload[1].toInt() and 0xFF, *block.map { it.toInt() and 0xFF }.toIntArray())
            }
            0x37 -> respond(0x77, 0x00)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        if (replyPos >= reply.size) throw TransportTimeoutException()
        val n = minOf(length, reply.size - replyPos)
        reply.copyInto(buffer, offset, replyPos, replyPos + n)
        replyPos += n
        return n
    }

    private fun respond(vararg payload: Int) {
        val body = ByteArray(payload.size) { payload[it].toByte() }
        reply = Slip.encode(byteArrayOf(0x01, 0xFF.toByte(), (body.size + 1).toByte(), 0x00) + body)
        replyPos = 0
    }

    /** One manifest page: a 32-byte record per dive, newest first, then a terminator. */
    private fun manifest(): ByteArray {
        val page = ByteArray(32 * 48) { 0xFF.toByte() }
        dives.forEachIndexed { i, (fingerprint, address, _) ->
            val r = i * 32
            page[r] = 0xA5.toByte()
            page[r + 1] = 0xC4.toByte()
            for (k in 0 until 4) page[r + 4 + k] = fingerprint.substring(k * 2, k * 2 + 2).toInt(16).toByte()
            for (k in 0 until 4) page[r + 20 + k] = (address ushr (8 * (3 - k))).toByte()
        }
        return page
    }

    /** Literal-only 9-bit run-length stream with the end code. Blobs up to 32 bytes skip the XOR stage. */
    private fun lre(data: ByteArray): ByteArray {
        val codes = data.map { 0x100 or (it.toInt() and 0xFF) } + 0
        val bits = codes.flatMap { code -> (8 downTo 0).map { (code ushr it) and 1 } }
        return ByteArray((bits.size + 7) / 8) { byte ->
            (0 until 8).fold(0) { v, bit -> (v shl 1) or (bits.getOrNull(byte * 8 + bit) ?: 0) }.toByte()
        }
    }

    companion object {
        const val MANIFEST = 0xE0000000L
        const val DIVE_BASE = 0xC0000000L
    }
}
