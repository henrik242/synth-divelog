package no.synth.divelog.core.logbook.download

import kotlinx.coroutines.runBlocking
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DiveLogParser
import no.synth.divelog.core.divecomputer.DownloadCancelledException
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.ProtocolException
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.IncomingDive
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The download orchestration over a fake protocol, port and parser, into an in-memory logbook. */
class DownloadControllerTest {
    private val container = AppContainer(createDatabase())

    /** A dive the fake parser reads: [n] orders them in time; [readable] false makes it fail. */
    private fun dive(n: Int, readable: Boolean = true) =
        RawDive(fingerprint = "fp$n", data = byteArrayOf(if (readable) n.toByte() else -1), formatId = "fake")

    private val parser = object : DiveLogParser {
        override val formatId = "fake"

        override fun parse(raw: RawDive): IncomingDive {
            val n = raw.data[0].toInt()
            if (n < 0) throw ProtocolException("unreadable")
            return IncomingDive(
                startEpochSeconds = 1_700_000_000L + n * 86_400L,
                utcOffsetSeconds = 0,
                durationSeconds = 1_800,
                maxDepthMm = 20_000,
                rawData = raw.data,
                rawFormatId = raw.formatId,
                fingerprint = raw.fingerprint,
            )
        }
    }

    /** Serves [dives] newest first, stopping at the known fingerprint; then fails or blocks as told. */
    private class FakeProtocol(
        private val transport: Transport,
        private val dives: List<RawDive>,
        private val failWith: Exception? = null,
        private val blockAfter: Boolean = false,
    ) : DiveComputerProtocol {
        var knownFingerprint: String? = null

        override fun readDeviceInfo() = DeviceInfo(vendor = "Shearwater", model = "Petrel")

        override fun download(
            knownFingerprint: String?,
            listener: DownloadListener,
            cancel: CancellationSignal,
            limit: Int?,
        ): List<RawDive> {
            this.knownFingerprint = knownFingerprint
            listener.onDeviceInfo(readDeviceInfo())
            val fresh = dives.takeWhile { it.fingerprint != knownFingerprint }
            fresh.forEachIndexed { i, d -> listener.onDiveDownloaded(i, d) }
            failWith?.let { throw it }
            if (blockAfter) transport.read(ByteArray(1), 0, 1, 60_000) // a stuck link
            if (cancel.isCancelled()) throw DownloadCancelledException()
            return fresh
        }
    }

    /** A port whose transport blocks reads until it is closed. */
    private class FakePorts : SerialPorts {
        val closed = CountDownLatch(1)
        val transport = object : Transport {
            override fun open() {}
            override fun write(data: ByteArray) {}
            override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
                closed.await(timeoutMs, TimeUnit.MILLISECONDS)
                throw TransportClosedException()
            }
            override fun close() = closed.countDown()
        }
        override val downloadSupported = true
        override fun list() = listOf(SerialPortInfo("bt:AA:BB:CC:DD:EE:01", "Petrel"))
        override suspend fun open(id: String, params: SerialParams): Transport = transport
    }

    private fun controller(ports: FakePorts, protocol: (Transport) -> DiveComputerProtocol) =
        DownloadController(container, ports, protocolFor = { _, t -> protocol(t) }, parserFor = { parser })

    private fun download(
        controller: DownloadController,
        port: String = "bt:AA:BB:CC:DD:EE:01",
        amount: DownloadAmount = DownloadAmount.NewOnly,
        cancel: () -> Boolean = { false },
    ) = runBlocking {
        controller.download(
            type = DiveComputerType.SHEARWATER_PETREL,
            portId = port,
            cancel = cancel,
            onProgress = { _, _ -> },
            amount = amount,
            portDescriptor = port,
        )
    }

    @Test
    fun anUnreadableDiveIsCountedAndFetchedAgainNextTime() {
        val dives = listOf(dive(3), dive(2, readable = false), dive(1))
        val ports = FakePorts()
        val message = download(controller(ports) { FakeProtocol(it, dives) })

        assertEquals("Imported 1 new dive, 1 dive could not be read, left 1 newer for the next download", message)
        assertEquals(1, container.dives.allDives().size)

        // The next incremental download stops at the oldest dive, so the unreadable one comes again.
        var second: FakeProtocol? = null
        download(controller(FakePorts()) { t -> FakeProtocol(t, dives).also { second = it } })
        assertEquals("fp1", second?.knownFingerprint)
    }

    @Test
    fun everyDiveUnreadableIsNotNoNewDives() {
        val dives = listOf(dive(2, readable = false), dive(1, readable = false))
        val message = download(controller(FakePorts()) { FakeProtocol(it, dives) })
        assertEquals("2 dives could not be read", message)
    }

    @Test
    fun aDownloadThatFailsPartwayImportsWhatItRead() {
        val dives = listOf(dive(2), dive(1))
        val message = download(controller(FakePorts()) { FakeProtocol(it, dives, failWith = TransportException("link lost")) })
        assertEquals("Download stopped after 2 dives: link lost. Imported 2 new dives", message)
        assertEquals(2, container.dives.allDives().size)
    }

    @Test
    fun cancellingClosesTheLinkAndKeepsTheDivesRead() {
        val ports = FakePorts()
        val start = System.currentTimeMillis()
        val cancelled = AtomicBoolean(false)
        Thread {
            Thread.sleep(300)
            cancelled.set(true)
        }.start()
        val message = download(controller(ports) { FakeProtocol(it, listOf(dive(1)), blockAfter = true) }, cancel = { cancelled.get() })
        assertEquals("Cancelled. Imported 1 new dive", message)
        assertEquals(0, ports.closed.count)
        assertTrue(System.currentTimeMillis() - start < 10_000, "the blocked read should end on cancel")
    }

    @Test
    fun twoPetrelsOnDifferentAddressesAreTwoComputers() {
        // A Petrel stored before addresses were part of the key is claimed by the next download.
        val legacy = container.devices.add(
            Device(vendor = "Shearwater", model = "Petrel", bluetoothAddress = "serial-spp:shearwater-petrel"),
        )
        download(controller(FakePorts()) { FakeProtocol(it, listOf(dive(1))) })
        download(controller(FakePorts()) { FakeProtocol(it, listOf(dive(5))) }, port = "bt:AA:BB:CC:DD:EE:02")

        val petrels = container.devices.all().filter { it.model == "Petrel" }
        assertEquals(2, petrels.size)
        assertEquals("serial-spp:shearwater-petrel@AA:BB:CC:DD:EE:01", container.devices.get(legacy)?.bluetoothAddress)
        assertTrue(petrels.any { it.bluetoothAddress == "serial-spp:shearwater-petrel@AA:BB:CC:DD:EE:02" })
    }

    @Test
    fun aBluetoothAddressIsTheSameComputerHoweverItIsSpelled() {
        download(controller(FakePorts()) { FakeProtocol(it, listOf(dive(1))) }, port = "bt:aa:bb:cc:dd:ee:01")
        download(controller(FakePorts()) { FakeProtocol(it, listOf(dive(2))) }, port = "aa-bb-cc-dd-ee-01")
        assertEquals(1, container.devices.all().size)
    }

    @Test
    fun aVyper2IsNotTakenForAVyper() {
        val vyper2 = Device(vendor = "Suunto", model = "HelO2", bluetoothAddress = "usb-serial:suunto-vyper2:01020304")
        assertFalse(DiveComputerType.SUUNTO_VYPER.owns(vyper2))
        assertTrue(DiveComputerType.SUUNTO_VYPER2.owns(vyper2))
    }
}
