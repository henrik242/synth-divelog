package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.transport.Direction
import no.synth.divelog.core.divecomputer.transport.ReplayTransport
import no.synth.divelog.core.divecomputer.transport.Transcript
import no.synth.divelog.core.divecomputer.transport.TransportEvent
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end download over the simulated read command, plus a ReplayTransport
 * check of a single paged read against a fixed transcript.
 */
class SuuntoVyperProtocolTest {
    @Test
    fun downloadsAndParsesTheSyntheticDive() {
        val image = SyntheticVyper.squareDiveImage()
        val transport = FakeSuuntoVyperDevice(image.memory)

        var captured: DeviceInfo? = null
        var diveCount = -1
        val listener = object : DownloadListener {
            override fun onDeviceInfo(info: DeviceInfo) { captured = info }
            override fun onDiveCount(total: Int) { diveCount = total }
        }

        val raw = SuuntoVyperProtocol(transport).download(knownFingerprint = null, listener = listener)
        assertEquals(1, raw.size)
        assertEquals("Suunto", captured?.vendor)
        assertEquals("Zoop", captured?.model)
        assertEquals(1, diveCount)

        val dive = SuuntoVyperParser().parse(raw[0])
        assertEquals(66 * 3048 / 10, dive.maxDepthMm)
        assertEquals(6 * 20, dive.durationSeconds)
    }

    @Test
    fun knownFingerprintStopsAtAlreadyStoredDive() {
        val image = SyntheticVyper.squareDiveImage()
        val fingerprint = SuuntoVyperDump.extract(image.ring, image.pointer).first().fingerprint
        val raw = SuuntoVyperProtocol(FakeSuuntoVyperDevice(image.memory))
            .download(knownFingerprint = fingerprint)
        assertEquals(0, raw.size)
    }

    @Test
    fun replayTransportServesAPagedRead() {
        // host -> 05 00 24 01 crc ; device -> 05 00 24 01 <byte> crc
        val request = SuuntoCrc.appended(byteArrayOf(0x05, 0x00, 0x24, 0x01))
        val reply = SuuntoCrc.appended(byteArrayOf(0x05, 0x00, 0x24, 0x01, SyntheticVyper.MODEL_ZOOP.toByte()))
        val transcript = Transcript(
            listOf(
                TransportEvent(Direction.WRITE, request),
                TransportEvent(Direction.READ, reply),
            ),
        )
        val memory = SuuntoVyperMemory(ReplayTransport(transcript, strictWrites = true))
        assertEquals(SyntheticVyper.MODEL_ZOOP, memory.readByte(0x24))
    }
}
