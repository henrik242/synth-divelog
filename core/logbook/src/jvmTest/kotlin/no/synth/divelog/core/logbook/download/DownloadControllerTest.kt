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
import no.synth.divelog.core.transport.BleScanner
import no.synth.divelog.core.transport.ScannedDevice
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
        private val info: DeviceInfo = DeviceInfo(vendor = "Shearwater", model = "Petrel"),
    ) : DiveComputerProtocol {
        var knownFingerprint: String? = null

        override fun readDeviceInfo() = info

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
        type: DiveComputerType = DiveComputerType.SHEARWATER_PETREL,
        review: (suspend (List<DownloadedDive>) -> List<DiveAction>)? = null,
    ) = runBlocking {
        controller.download(
            type = type,
            portId = port,
            cancel = cancel,
            onProgress = { _, _ -> },
            amount = amount,
            portDescriptor = port,
            review = review ?: { dives -> dives.map { it.status.default } },
        )
    }

    @Test
    fun allNewDivesAreImportedWithoutAReview() {
        val message = download(controller(FakePorts()) { FakeProtocol(it, listOf(dive(2), dive(1))) }, amount = DownloadAmount.All) {
            error("nothing to review")
        }
        assertEquals("Imported 2 new dives", message)
    }

    @Test
    fun aStoredDiveIsRefreshedOnlyWhenChecked() {
        val dives = listOf(dive(2), dive(1))
        download(controller(FakePorts()) { FakeProtocol(it, dives) })
        var reviewed: List<DownloadedDive> = emptyList()
        val message = download(controller(FakePorts()) { FakeProtocol(it, dives) }, amount = DownloadAmount.All) { list ->
            reviewed = list
            listOf(DiveAction.REFRESH, DiveAction.SKIP)
        }
        assertEquals(listOf(DiveAction.SKIP, DiveAction.SKIP), reviewed.map { it.status.default })
        assertTrue(reviewed.all { it.status is DiveStatus.Stored && !(it.status as DiveStatus.Stored).secondComputer })
        assertEquals("Refreshed 1 from the computer, skipped 1 already in the logbook", message)
        assertEquals(2, container.dives.allDives().size)
    }

    @Test
    fun aJoinedDiveIsNotRefreshed() {
        val dives = listOf(dive(2), dive(1))
        download(controller(FakePorts()) { FakeProtocol(it, dives) })
        container.dives.mergeDives(container.dives.allDives().map { it.id })
        var statuses: List<DiveStatus> = emptyList()
        val message = download(controller(FakePorts()) { FakeProtocol(it, dives) }, amount = DownloadAmount.All) { list ->
            statuses = list.map { it.status }
            list.map { DiveAction.REFRESH }
        }
        assertTrue(statuses.all { it is DiveStatus.Stored && it.joined && it.checkedAction == DiveAction.SKIP })
        assertEquals("Skipped 2 already in the logbook", message)
    }

    @Test
    fun aDeletedDiveStaysDeletedUnlessTheUserTakesItBack() {
        val dives = listOf(dive(2), dive(1))
        download(controller(FakePorts()) { FakeProtocol(it, dives) })
        val newest = container.dives.allDives().maxBy { it.startEpochSeconds }
        container.dives.deleteDive(newest.id)

        // The incremental download stops at the deleted dive rather than bringing it back.
        var incremental: FakeProtocol? = null
        assertEquals("No new dives", download(controller(FakePorts()) { t -> FakeProtocol(t, dives).also { incremental = it } }))
        assertEquals("fp2", incremental?.knownFingerprint)

        var statuses: List<DiveStatus> = emptyList()
        val left = download(controller(FakePorts()) { FakeProtocol(it, dives) }, amount = DownloadAmount.All) { list ->
            statuses = list.map { it.status }
            list.map { it.status.default }
        }
        assertEquals(DiveStatus.Deleted, statuses.first())
        assertEquals("Skipped 1 already in the logbook, left out 1", left)
        assertEquals(1, container.dives.allDives().size)

        val back = download(controller(FakePorts()) { FakeProtocol(it, dives) }, amount = DownloadAmount.All) { list ->
            list.map { it.status.checkedAction.takeIf { _ -> it.status == DiveStatus.Deleted } ?: DiveAction.SKIP }
        }
        assertEquals("Imported 1 new dive, skipped 1 already in the logbook", back)
        assertEquals(2, container.dives.allDives().size)
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

    @Test
    fun eachComputerIsOfferedOnlyThePortsItsLinkCanUse() {
        val usb = SerialPortInfo("usb:0", "cable", SerialPortKind.USB_SERIAL)
        val spp = SerialPortInfo("bt:AA:BB:CC:DD:EE:01", "Petrel", SerialPortKind.BLUETOOTH_SPP)
        fun scanned(id: String, name: String?) = SerialPortInfo("ble:$id", "$name", SerialPortKind.BLUETOOTH_LE, advertisedName = name)
        val peregrine = scanned("AA:BB:CC:DD:EE:02", "Peregrine TX")
        val teric = scanned("AA:BB:CC:DD:EE:03", "Teric")
        val other = scanned("AA:BB:CC:DD:EE:08", "Heart rate")
        val nameless = scanned("AA:BB:CC:DD:EE:04", null)
        fun ports(type: DiveComputerType) = listOf(usb, spp, peregrine, teric, other, nameless).filter { type.canUse(it) }
        assertEquals(listOf(usb), ports(DiveComputerType.SUUNTO_VYPER2))
        assertEquals(listOf(usb, spp), ports(DiveComputerType.SHEARWATER_PETREL))
        // Any newer Shearwater by its name, and a nameless one could be one.
        assertEquals(listOf(usb, spp, peregrine, teric, nameless), ports(DiveComputerType.SHEARWATER))
        // On iOS (LE only) the Petrel 1 is not offered at all.
        val serialOnly = setOf(SerialPortKind.USB_SERIAL, SerialPortKind.BLUETOOTH_SPP)
        assertTrue(DiveComputerType.SHEARWATER_PETREL.reachableOver(serialOnly))
        assertTrue(DiveComputerType.SHEARWATER.reachableOver(setOf(SerialPortKind.BLUETOOTH_LE)))
        assertFalse(DiveComputerType.SHEARWATER_PETREL.reachableOver(setOf(SerialPortKind.BLUETOOTH_LE)))
        // Scanned for only where there is Bluetooth LE (not on Linux or Windows).
        assertTrue(DiveComputerType.SHEARWATER.foundByScan(setOf(SerialPortKind.BLUETOOTH_SPP, SerialPortKind.BLUETOOTH_LE)))
        assertFalse(DiveComputerType.SHEARWATER.foundByScan(serialOnly))
    }

    @Test
    fun aPairedShearwaterIsPreselectedOverAnyOtherPairedDevice() {
        val headset = SerialPortInfo("bt:AA:BB:CC:DD:EE:06", "Headset (Bluetooth)", SerialPortKind.BLUETOOTH_SPP)
        val pairedPetrel = SerialPortInfo("bt:AA:BB:CC:DD:EE:07", "Perdix (Bluetooth)", SerialPortKind.BLUETOOTH_SPP)
        val scanned = SerialPortInfo("ble:0F3C", "0F3C (Bluetooth LE)", SerialPortKind.BLUETOOTH_LE)
        val type = DiveComputerType.SHEARWATER
        assertEquals(pairedPetrel, type.likelyPort(listOf(headset, scanned, pairedPetrel)))
        assertEquals(scanned, type.likelyPort(listOf(headset, scanned)))
        assertEquals(headset, type.likelyPort(listOf(headset)))
    }

    @Test
    fun aShearwaterIsOneComputerOnEveryPlatformAndLink() {
        val perdix = DeviceInfo(vendor = "Shearwater", model = "Perdix", serial = "87654321")
        val controller = controller(FakePorts()) { FakeProtocol(it, listOf(dive(1)), info = perdix) }
        // Bluetooth Classic, Android LE (by address) and CoreBluetooth (iOS, macOS: a UUID of its own).
        download(controller, port = "bt:AA:BB:CC:DD:EE:05", type = DiveComputerType.SHEARWATER)
        download(controller, port = "ble:aa:bb:cc:dd:ee:02", type = DiveComputerType.SHEARWATER)
        download(controller, port = "ble:0F3C2A1B-7D44-4E2A-9C61-5B8E2D9A1F00", type = DiveComputerType.SHEARWATER)
        val device = container.devices.all().single()
        assertEquals("shearwater:87654321", device.bluetoothAddress)
        assertEquals("Perdix", device.model)
        assertTrue(DiveComputerType.SHEARWATER.owns(device))
        assertFalse(DiveComputerType.SHEARWATER_PETREL.owns(device))
    }

    @Test
    fun aScannedComputerWithABlankNameIsOffered() {
        // CoreBluetooth can report an empty name; the scan keeps such a computer, so the picker must too.
        val scanner = object : BleScanner {
            override fun scan() {}
            override fun stop() {}
            override fun found() = listOf(ScannedDevice("0F3C2A1B", ""))
            override fun problem(): String? = null
        }
        val port = blePorts(scanner).single()
        assertEquals("0F3C2A1B (Bluetooth LE)", port.label)
        assertTrue(DiveComputerType.SHEARWATER.canUse(port))
    }
}
