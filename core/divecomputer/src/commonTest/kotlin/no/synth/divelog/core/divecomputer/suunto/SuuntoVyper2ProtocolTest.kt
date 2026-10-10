package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives [SuuntoVyper2Protocol] end to end against an in-memory HelO2 device
 * ([SimulatedVyper2Device]) built from a synthetic ring: reads identity, walks the ring
 * and honours the fingerprint and limit arguments.
 */
class SuuntoVyper2ProtocolTest {
    private fun device() = SimulatedVyper2Device(
        SyntheticVyper2.memory(
            listOf(SyntheticVyper2.helo2Record(maxDepthCm = 300), SyntheticVyper2.helo2Record(maxDepthCm = 600)),
        ),
    )

    @Test
    fun readsDeviceIdentity() {
        val info = SuuntoVyper2Protocol(device()).readDeviceInfo()
        assertEquals(DeviceInfo(vendor = "Suunto", model = "HelO2", serial = "01020304", firmware = "0.1.4"), info)
    }

    @Test
    fun downloadsBothDivesNewestFirst() {
        var reportedInfo: DeviceInfo? = null
        val downloaded = mutableListOf<Int>()
        val progress = mutableListOf<Pair<Int, Int>>()
        val dives = SuuntoVyper2Protocol(device()).download(
            knownFingerprint = null,
            listener = object : DownloadListener {
                override fun onDeviceInfo(info: DeviceInfo) { reportedInfo = info }
                override fun onDiveDownloaded(index: Int, dive: RawDive) { downloaded += index }
                override fun onProgress(current: Int, total: Int) { progress += current to total }
            },
            cancel = CancellationSignal.NONE,
        )
        assertEquals(2, dives.size)
        assertEquals("HelO2", reportedInfo?.model)
        assertEquals(listOf(0, 1), downloaded)
        assertEquals(6000, SuuntoVyper2Parser().parse(dives[0]).maxDepthMm)
        assertEquals(progress.last().first, progress.last().second) // progress completes
    }

    @Test
    fun stopsAtKnownFingerprint() {
        // The newest dive's fingerprint is already stored, so only nothing newer remains.
        val all = SuuntoVyper2Protocol(device()).download(knownFingerprint = null)
        val newest = all.first().fingerprint
        val fresh = SuuntoVyper2Protocol(device()).download(knownFingerprint = newest)
        assertEquals(0, fresh.size)
    }

    @Test
    fun limitCapsTheResult() {
        val dives = SuuntoVyper2Protocol(device()).download(knownFingerprint = null, limit = 1)
        assertEquals(1, dives.size)
        assertEquals(6000, SuuntoVyper2Parser().parse(dives.first()).maxDepthMm)
    }

    @Test
    fun incrementalDownloadReadsOnlyTheNewDives() {
        val records = (1..20).map { SyntheticVyper2.helo2Record(maxDepthCm = it * 100) }
        val full = SimulatedVyper2Device(SyntheticVyper2.memory(records))
        val all = SuuntoVyper2Protocol(full).download(knownFingerprint = null)
        assertEquals(20, all.size)

        val partial = SimulatedVyper2Device(SyntheticVyper2.memory(records))
        val fresh = SuuntoVyper2Protocol(partial).download(knownFingerprint = all[2].fingerprint)
        assertEquals(all.take(2).map { it.fingerprint }, fresh.map { it.fingerprint })
        assertTrue(partial.memoryReads * 4 < full.memoryReads, "${partial.memoryReads} vs ${full.memoryReads} reads")
    }

    @Test
    fun readsDivesAcrossTheRingWrap() {
        val records = listOf(300, 600, 900).map { SyntheticVyper2.helo2Record(maxDepthCm = it) }
        val memory = SyntheticVyper2.memory(records, startAt = SuuntoVyper2Dump.RB_PROFILE_END - 100)
        val dives = SuuntoVyper2Protocol(SimulatedVyper2Device(memory)).download(knownFingerprint = null)
        assertEquals(listOf(9000, 6000, 3000), dives.map { SuuntoVyper2Parser().parse(it).maxDepthMm })
        val ring = memory.copyOfRange(SuuntoVyper2Dump.RB_PROFILE_BEGIN, SuuntoVyper2Dump.RB_PROFILE_END)
        val header = memory.copyOfRange(SuuntoVyper2Dump.HEADER_OFFSET, SuuntoVyper2Dump.HEADER_OFFSET + SuuntoVyper2Dump.HEADER_SIZE)
        assertEquals(SuuntoVyper2Dump.extract(ring, header).map { it.fingerprint }, dives.map { it.fingerprint })
    }

    @Test
    fun corruptBeginStopsAtTheOldestDive() {
        val records = listOf(300, 600).map { SyntheticVyper2.helo2Record(maxDepthCm = it) }
        val device = SimulatedVyper2Device(SyntheticVyper2.memory(records, validBegin = false))
        val dives = SuuntoVyper2Protocol(device).download(knownFingerprint = null)
        assertEquals(2, dives.size)
        assertTrue(device.memoryReads < 10, "read ${device.memoryReads} pages")
    }

    @Test
    fun readsTheIdentityOnceForAnIncrementalDownload() {
        val device = device()
        val protocol = SuuntoVyper2Protocol(device)
        protocol.readDeviceInfo()
        val afterInfo = device.memoryReads
        protocol.download(knownFingerprint = null)
        val again = device()
        SuuntoVyper2Protocol(again).download(knownFingerprint = null)
        // The download on the same instance skips the serial read the fresh one makes.
        assertEquals(again.memoryReads - 1, device.memoryReads - afterInfo)
    }

    @Test
    fun deliversDivesBeforeAFailure() {
        val records = (1..5).map { SyntheticVyper2.helo2Record(maxDepthCm = it * 100) }
        val device = SimulatedVyper2Device(SyntheticVyper2.memory(records))
        val received = mutableListOf<Int>()
        // Cancel once two dives are in: the walk stops, the two are already delivered.
        runCatching {
            SuuntoVyper2Protocol(device).download(
                knownFingerprint = null,
                listener = object : DownloadListener {
                    override fun onDiveDownloaded(index: Int, dive: RawDive) { received += index }
                },
                cancel = CancellationSignal { received.size >= 2 },
            )
        }
        assertEquals(listOf(0, 1), received)
    }
}
