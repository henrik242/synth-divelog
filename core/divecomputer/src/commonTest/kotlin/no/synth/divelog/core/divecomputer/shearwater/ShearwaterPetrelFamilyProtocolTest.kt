package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DownloadCancelledException
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.transport.Slip
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

/**
 * Drives [ShearwaterPetrelFamilyProtocol] against an in-memory Peregrine or Petrel 2
 * ([FakeShearwaterDevice] behind [BlePackets] or [SlipStream]): identifiers, a manifest and
 * compressed dives.
 */
class ShearwaterPetrelFamilyProtocolTest {
    private val dives = listOf(
        FakeDive("0000000a", 0x100L, ByteArray(20) { 10 }), // newest
        FakeDive("0000000b", 0x200L, ByteArray(20) { 11 }), // oldest
    )

    private fun device(
        logBase: Long,
        dives: List<FakeDive> = this.dives,
        dropAfterDives: Int = Int.MAX_VALUE,
        model: Int = PEREGRINE,
    ) =
        FakeShearwaterDevice(
            dives,
            identifiers = mapOf(
                0x8010 to "1a2b3c4d".encodeToByteArray(),
                0x8011 to "V92".encodeToByteArray(),
                0x8021 to byteArrayOf(0) + be32(logBase) + byteArrayOf(0, 0, 0, 0),
                0x8060 to byteArrayOf(model.toByte()),
            ),
            dropAfterDives = dropAfterDives,
        )

    @Test
    fun readsTheDeviceAndItsRecordFormatDives() {
        val device = device(logBase = 0x80000000L)
        var reported: DeviceInfo? = null
        val result = ShearwaterPetrelFamilyProtocol(BlePackets(device)).download(
            knownFingerprint = null,
            listener = object : DownloadListener {
                override fun onDeviceInfo(info: DeviceInfo) { reported = info }
            },
        )
        assertEquals(DeviceInfo(vendor = "Shearwater", model = "Peregrine", serial = "1A2B3C4D", firmware = "V92"), reported)
        assertEquals(listOf(0x80000200L, 0x80000100L), device.diveReads)
        assertEquals(listOf("0000000a", "0000000b"), result.map { it.fingerprint })
        result.forEach { assertEquals(PredatorParser.PNF_FORMAT_ID, it.formatId) }
        assertContentEquals(dives[0].data, result[0].data)
        // The session ends with the shutdown command, which gets no reply.
        assertContentEquals(SHUTDOWN, device.commands.last())
    }

    @Test
    fun aPetrel2SpeaksThePlainStreamOverBluetoothClassicAndPacketsOverLe() {
        for (overLe in listOf(false, true)) {
            val device = device(logBase = 0xC0000000L, model = PETREL_2)
            val link = if (overLe) BlePackets(device, maxWriteSize = 20) else SlipStream(device)
            val protocol = ShearwaterPetrelFamilyProtocol(link)
            assertEquals(DeviceInfo(vendor = "Shearwater", model = "Petrel 2", serial = "1A2B3C4D", firmware = "V92"), protocol.readDeviceInfo())
            val result = protocol.download(knownFingerprint = null)
            assertEquals(listOf(0xC0000200L, 0xC0000100L), device.diveReads)
            assertEquals(listOf(PredatorParser.PETREL_FORMAT_ID, PredatorParser.PETREL_FORMAT_ID), result.map { it.formatId })
            assertContentEquals(SHUTDOWN, device.commands.last())
        }
    }

    @Test
    fun eachModelIsNamedFromItsNumber() {
        fun model(number: Int) = ShearwaterPetrelFamilyProtocol(BlePackets(device(logBase = 0x80000000L, model = number))).readDeviceInfo().model
        assertEquals("Perdix AI", model(6))
        assertEquals("Teric", model(8))
        assertEquals("Tern", model(12))
        assertEquals("Peregrine TX", model(13))
        assertEquals("Perdix 3", model(14))
    }

    @Test
    fun anUnknownModelIsRefusedAndStillShutsDown() {
        val device = device(logBase = 0x80000000L, model = 99)
        val e = assertFailsWith<ProtocolException> {
            ShearwaterPetrelFamilyProtocol(BlePackets(device)).download(knownFingerprint = null)
        }
        assertEquals("This Shearwater (model 99) is not supported yet.", e.message)
        assertEquals(emptyList(), device.diveReads)
        assertContentEquals(SHUTDOWN, device.commands.last())
    }

    @Test
    fun theModelNumberIsItsFirstByte() {
        assertFailsWith<ProtocolException> { ShearwaterPetrelFamilyProtocol.modelNumber(ByteArray(0)) }
        assertEquals(200, ShearwaterPetrelFamilyProtocol.modelNumber(byteArrayOf(200.toByte(), 0)))
    }

    @Test
    fun olderFirmwareServesPetrelBlocks() {
        val device = device(logBase = 0x90000000L)
        val result = ShearwaterPetrelFamilyProtocol(BlePackets(device)).download(knownFingerprint = "0000000b")
        assertEquals(listOf(0xC0000100L), device.diveReads)
        assertEquals(listOf(PredatorParser.PETREL_FORMAT_ID), result.map { it.formatId })
    }

    @Test
    fun aDefaultMtuLinkCarriesRequestsThatNeedTwoPackets() {
        // C0 DB C0 C0: four escaped address bytes make the init 19 bytes, more than 18 fit after the lead.
        val escaped = listOf(FakeDive("0000000a", 0xDBC0C0L, ByteArray(20) { 10 }))
        val link = BlePackets(device(logBase = 0xC0000000L, dives = escaped), maxWriteSize = 20, notificationSize = 20)
        val result = ShearwaterPetrelFamilyProtocol(link).download(knownFingerprint = null)
        assertContentEquals(escaped[0].data, result.single().data)
        assertEquals(true, link.multiPacketFrames > 0, "no request was split")
    }

    @Test
    fun largeNotificationsAndLeadOnlyPacketsAreRead() {
        val device = device(logBase = 0x80000000L)
        val result = ShearwaterPetrelFamilyProtocol(BlePackets(device, notificationSize = 244, leadOnlyPackets = true))
            .download(knownFingerprint = null)
        assertEquals(listOf("0000000a", "0000000b"), result.map { it.fingerprint })
    }

    @Test
    fun deviceInfoIsReadOnce() {
        val device = device(logBase = 0x80000000L)
        val protocol = ShearwaterPetrelFamilyProtocol(BlePackets(device))
        protocol.readDeviceInfo()
        protocol.download(knownFingerprint = "0000000a")
        for (id in listOf(0x10, 0x11, 0x60)) {
            assertEquals(1, device.commands.count { it[0] == 0x22.toByte() && it[2] == id.toByte() }, "identifier 80${id.toString(16)}")
        }
    }

    @Test
    fun aLimitReadsOnlyTheNewest() {
        val device = device(logBase = 0x80000000L)
        val result = ShearwaterPetrelFamilyProtocol(BlePackets(device)).download(knownFingerprint = null, limit = 1)
        assertEquals(listOf("0000000a"), result.map { it.fingerprint })
        assertEquals(listOf(0x80000100L), device.diveReads)
    }

    @Test
    fun aDroppedLinkKeepsTheDivesReadAndStillShutsDown() {
        val device = device(logBase = 0x80000000L, dropAfterDives = 1)
        val delivered = mutableListOf<String>()
        val error = assertFailsWith<TransportException> {
            ShearwaterPetrelFamilyProtocol(BlePackets(device)).download(
                knownFingerprint = null,
                listener = object : DownloadListener {
                    override fun onDiveDownloaded(index: Int, dive: RawDive) { delivered += dive.fingerprint }
                },
            )
        }
        assertEquals("link dropped", error.message) // the drop, not something the shutdown raised
        assertEquals(listOf("0000000b"), delivered)
        assertContentEquals(SHUTDOWN, device.commands.last())
    }

    @Test
    fun cancellingStillShutsDown() {
        val device = device(logBase = 0x80000000L)
        assertFailsWith<DownloadCancelledException> {
            ShearwaterPetrelFamilyProtocol(BlePackets(device)).download(knownFingerprint = null, cancel = CancellationSignal { true })
        }
        assertContentEquals(SHUTDOWN, device.commands.last())
    }

    @Test
    fun anUnknownLogFormatStopsAndStillShutsDown() {
        val device = device(logBase = 0x12000000L)
        assertFailsWith<ProtocolException> {
            ShearwaterPetrelFamilyProtocol(BlePackets(device)).download(knownFingerprint = null)
        }
        assertContentEquals(SHUTDOWN, device.commands.last())
    }

    @Test
    fun theLogFormatTable() {
        fun format(base: Long) = ShearwaterPetrelFamilyProtocol.logFormat(byteArrayOf(0) + be32(base) + ByteArray(4))
        val records = ShearwaterPetrelFamilyProtocol.LogFormat(0x80000000L, PredatorParser.PNF_FORMAT_ID)
        val blocks = ShearwaterPetrelFamilyProtocol.LogFormat(0xC0000000L, PredatorParser.PETREL_FORMAT_ID)
        assertEquals(records, format(0x80000000L))
        for (base in listOf(0xC0000000L, 0x90000000L, 0xDD000000L)) assertEquals(blocks, format(base), base.toString(16))
        for (size in listOf(8, 10)) {
            assertFailsWith<ProtocolException> { ShearwaterPetrelFamilyProtocol.logFormat(ByteArray(size)) }
        }
    }

    @Test
    fun serialAndFirmwareDropTheirPadding() {
        fun serial(text: String) = ShearwaterPetrelFamilyProtocol.serial(text.encodeToByteArray())
        assertEquals("1A2B3C4D", serial("1a2b3c4d"))
        assertEquals("1A2B3C4D", serial("1A2B3C4D\u0000\u0000"))
        assertEquals("1A2B3C4D", serial(" 1A2B3C4D "))
        assertNull(serial("1A2B-3C4D"))
        assertNull(serial("1A2B3C"))
        assertNull(serial(""))
        assertEquals("V92", ShearwaterPetrelFamilyProtocol.firmware("V92\u0000\u0000 ".encodeToByteArray()))
        assertNull(ShearwaterPetrelFamilyProtocol.firmware(ByteArray(4)))
    }

    private companion object {
        const val PETREL_2 = 3
        const val PEREGRINE = 9
        val SHUTDOWN = byteArrayOf(0x2E, 0x90.toByte(), 0x20, 0x00)

        fun be32(v: Long) = ByteArray(4) { (v ushr (8 * (3 - it))).toByte() }
    }
}

/**
 * A [FakeShearwaterDevice] behind Shearwater's BLE packets. Each written packet must carry
 * a correct `[count] [index]` lead and fit [maxWriteSize]. Replies go
 * back as notifications of at most [notificationSize] bytes with their own lead, and with
 * [leadOnlyPackets] a lead-only packet after each.
 */
private class BlePackets(
    private val device: FakeShearwaterDevice,
    override val maxWriteSize: Int = ShearwaterBlePackets.PACKET_SIZE,
    private val notificationSize: Int = 20,
    private val leadOnlyPackets: Boolean = false,
) : Transport {
    var multiPacketFrames = 0
    private val incoming = ArrayList<Byte>()
    private val notifications = ArrayDeque<ByteArray>()
    private var expectedCount = -1
    private var nextIndex = 0

    override fun open() {}

    override fun close() {}

    override fun write(data: ByteArray) {
        require(data.size <= maxWriteSize) { "packet of ${data.size} bytes over $maxWriteSize" }
        val count = data[0].toInt() and 0xFF
        val index = data[1].toInt() and 0xFF
        if (nextIndex == 0) expectedCount = count
        require(count == expectedCount && index == nextIndex) { "lead $count/$index, expected $expectedCount/$nextIndex" }
        nextIndex++
        for (b in data.copyOfRange(2, data.size)) {
            if ((b.toInt() and 0xFF) != Slip.END) {
                incoming += b
                continue
            }
            require(nextIndex == expectedCount) { "frame ended in packet $nextIndex of $expectedCount" }
            if (expectedCount > 1) multiPacketFrames++
            nextIndex = 0
            val frame = Slip.unescape(incoming.toByteArray())
            incoming.clear()
            device.answer(frame.copyOfRange(4, frame.size))?.let(::respond)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        val packet = notifications.removeFirstOrNull() ?: throw TransportTimeoutException()
        packet.copyInto(buffer, offset)
        return packet.size
    }

    private fun respond(body: ByteArray) {
        val chunks = Slip.encode(FakeShearwaterDevice.replyFrame(body)).toList().chunked(notificationSize - 2)
        chunks.forEachIndexed { i, chunk ->
            notifications += byteArrayOf(chunks.size.toByte(), i.toByte()) + chunk.toByteArray()
            if (leadOnlyPackets) notifications += byteArrayOf(chunks.size.toByte(), i.toByte())
        }
    }
}
