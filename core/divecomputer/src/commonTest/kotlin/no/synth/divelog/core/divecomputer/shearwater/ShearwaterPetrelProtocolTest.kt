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
 * Drives [ShearwaterPetrelProtocol] against an in-memory Petrel ([FakeShearwaterDevice]
 * over a SLIP byte stream) that serves a manifest and run-length compressed dives.
 */
class ShearwaterPetrelProtocolTest {
    private val dives = listOf(
        FakeDive("0000000a", 0x100L, ByteArray(20) { 10 }), // newest
        FakeDive("0000000b", 0x200L, ByteArray(20) { 11 }),
        FakeDive("0000000c", 0x300L, ByteArray(20) { 12 }), // oldest
    )

    @Test
    fun readsOldestFirstAndReturnsNewestFirst() {
        val device = FakeShearwaterDevice(dives)
        val delivered = mutableListOf<Int>()
        val result = ShearwaterPetrelProtocol(SlipStream(device)).download(
            knownFingerprint = null,
            listener = object : DownloadListener {
                override fun onDiveDownloaded(index: Int, dive: RawDive) { delivered += index }
            },
        )
        assertEquals(listOf(0xC0000300L, 0xC0000200L, 0xC0000100L), device.diveReads)
        assertEquals(listOf(2, 1, 0), delivered)
        assertEquals(listOf("0000000a", "0000000b", "0000000c"), result.map { it.fingerprint })
        assertContentEquals(dives[0].data, result[0].data)
        // The session ends with the shutdown, as on the Peregrine.
        assertContentEquals(byteArrayOf(0x2E, 0x90.toByte(), 0x20, 0x00), device.commands.last())
    }

    @Test
    fun stopsAtTheKnownDive() {
        val device = FakeShearwaterDevice(dives)
        val result = ShearwaterPetrelProtocol(SlipStream(device)).download(knownFingerprint = "0000000b")
        assertEquals(listOf("0000000a"), result.map { it.fingerprint })
        assertEquals(listOf(0xC0000100L), device.diveReads)
    }

    @Test
    fun aLimitReadsOnlyTheNewest() {
        val device = FakeShearwaterDevice(dives)
        val result = ShearwaterPetrelProtocol(SlipStream(device)).download(knownFingerprint = null, limit = 1)
        assertEquals(listOf("0000000a"), result.map { it.fingerprint })
        assertEquals(listOf(0xC0000100L), device.diveReads)
    }

    @Test
    fun aPageOfDeletedDivesDoesNotEndTheManifest() {
        // A full first page of deleted records, then the dives on the next page.
        val pages = listOf(FakeShearwaterDevice.manifestPage(emptyList(), deleted = 48), FakeShearwaterDevice.manifestPage(dives))
        val device = FakeShearwaterDevice(dives, manifestPages = pages)
        val result = ShearwaterPetrelProtocol(SlipStream(device)).download(knownFingerprint = null)
        assertEquals(listOf("0000000a", "0000000b", "0000000c"), result.map { it.fingerprint })
    }

    @Test
    fun aDroppedLinkKeepsTheOlderDivesAlreadyRead() {
        val device = FakeShearwaterDevice(dives, dropAfterDives = 1)
        val delivered = mutableListOf<String>()
        assertFailsWith<TransportException> {
            ShearwaterPetrelProtocol(SlipStream(device)).download(
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

/** A [FakeShearwaterDevice] behind the Petrel's Bluetooth Classic byte stream: SLIP frames both ways. */
private class SlipStream(private val device: FakeShearwaterDevice) : Transport {
    private var reply = ByteArray(0)
    private var replyPos = 0

    override fun open() {}

    override fun close() {}

    override fun write(data: ByteArray) {
        val frame = Slip.unescape(data.copyOfRange(0, data.size - 1))
        val body = device.answer(frame.copyOfRange(4, frame.size)) ?: return
        reply = Slip.encode(FakeShearwaterDevice.replyFrame(body))
        replyPos = 0
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        if (replyPos >= reply.size) throw TransportTimeoutException()
        val n = minOf(length, reply.size - replyPos)
        reply.copyInto(buffer, offset, replyPos, replyPos + n)
        replyPos += n
        return n
    }
}
