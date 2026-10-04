package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DownloadListener
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Drives [SuuntoD9Protocol] end to end against an in-memory HelO2 device
 * ([FakeSuuntoD9Device]) built from a synthetic ring: reads identity, walks the ring
 * and honours the fingerprint and limit arguments.
 */
class SuuntoD9ProtocolTest {
    private fun device() = FakeSuuntoD9Device(
        SyntheticD9.memory(
            listOf(SyntheticD9.helo2Record(maxDepthCm = 300), SyntheticD9.helo2Record(maxDepthCm = 600)),
        ),
    )

    @Test
    fun readsDeviceIdentity() {
        val info = SuuntoD9Protocol(device()).readDeviceInfo()
        assertEquals(DeviceInfo(vendor = "Suunto", model = "HelO2", serial = "01020304", firmware = "0.1.4"), info)
    }

    @Test
    fun downloadsBothDivesNewestFirst() {
        var reportedInfo: DeviceInfo? = null
        var reportedCount = -1
        val progress = mutableListOf<Pair<Int, Int>>()
        val dives = SuuntoD9Protocol(device()).download(
            knownFingerprint = null,
            listener = object : DownloadListener {
                override fun onDeviceInfo(info: DeviceInfo) { reportedInfo = info }
                override fun onDiveCount(total: Int) { reportedCount = total }
                override fun onProgress(current: Int, total: Int) { progress += current to total }
            },
            cancel = CancellationSignal.NONE,
        )
        assertEquals(2, dives.size)
        assertEquals("HelO2", reportedInfo?.model)
        assertEquals(2, reportedCount)
        assertEquals(6000, SuuntoD9Parser().parse(dives[0]).maxDepthMm)
        assertEquals(progress.last().first, progress.last().second) // progress completes
    }

    @Test
    fun stopsAtKnownFingerprint() {
        // The newest dive's fingerprint is already stored, so only nothing newer remains.
        val all = SuuntoD9Protocol(device()).download(knownFingerprint = null)
        val newest = all.first().fingerprint
        val fresh = SuuntoD9Protocol(device()).download(knownFingerprint = newest)
        assertEquals(0, fresh.size)
    }

    @Test
    fun limitCapsTheResult() {
        val dives = SuuntoD9Protocol(device()).download(knownFingerprint = null, limit = 1)
        assertEquals(1, dives.size)
        assertEquals(6000, SuuntoD9Parser().parse(dives.first()).maxDepthMm)
    }
}
