package no.synth.divelog.core.transport

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCentralManagerOptionShowPowerAlertKey
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicPropertyIndicate
import platform.CoreBluetooth.CBCharacteristicPropertyNotify
import platform.CoreBluetooth.CBCharacteristicPropertyWrite
import platform.CoreBluetooth.CBCharacteristicPropertyWriteWithoutResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.darwin.NSObject
import platform.darwin.dispatch_queue_create
import platform.darwin.dispatch_sync
import kotlin.concurrent.Volatile
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * A Bluetooth LE dive computer over CoreBluetooth, the Apple counterpart of Android's
 * BleGattTransport: writes go to the serial characteristic, one GATT write each, and its
 * notifications come back through a [PacketQueue]. [identifier] is the peripheral's
 * CoreBluetooth UUID, as [CoreBluetoothScanner] reports it.
 *
 * [open] waits for Bluetooth to be on, finds the peripheral (known to the system, else by a
 * scan), connects, discovers the services and the serial characteristic
 * ([ShearwaterBle.serialServices]) and turns on notifications; the system pairs on the way
 * if the device asks. CoreBluetooth is called and calls back on a queue of its own, so the
 * blocking calls work from any thread but that one. [close] from another thread ends a wait
 * in progress.
 *
 * iOS uses it directly; the desktop app on macOS runs it in the ble-bridge helper (BleBridge.kt).
 */
@OptIn(ExperimentalForeignApi::class)
class CoreBluetoothTransport(private val identifier: String) : Transport {
    private val notifications = PacketQueue()
    private val events = Channel<Event>(Channel.UNLIMITED)
    private val disconnected = Channel<Unit>(Channel.CONFLATED)
    private val delegate = Delegate()
    private val queue = dispatch_queue_create("ble-link", null)

    // No system "Turn on Bluetooth" alert: the picker already says so (CoreBluetoothScanner).
    private val central = CBCentralManager(delegate, queue, mapOf<Any?, Any>(CBCentralManagerOptionShowPowerAlertKey to false))

    @Volatile private var peripheral: CBPeripheral? = null
    @Volatile private var characteristic: CBCharacteristic? = null
    @Volatile private var closed = false
    @Volatile private var lost: TransportException? = null
    @Volatile private var writeType = CBCharacteristicWriteWithResponse

    /** One callback that ends a setup step, a write, or the link. */
    private sealed interface Event {
        data object PoweredOn : Event
        data class Discovered(val peripheral: CBPeripheral) : Event
        data object Connected : Event
        data object ServicesDiscovered : Event
        data object CharacteristicsDiscovered : Event
        data object NotifyingOrWritten : Event
        data object ReadyToWrite : Event
        data class Failed(val error: NSError?) : Event
        data class Ended(val reason: TransportException) : Event
    }

    override fun open() {
        if (closed) throw TransportClosedException()
        try {
            connect()
        } catch (e: TransportException) {
            close()
            throw e
        }
    }

    private fun connect() {
        await<Event.PoweredOn>("Bluetooth to turn on")
        val p = find()
        peripheral = p
        onQueue {
            p.delegate = delegate
            central.connectPeripheral(p, null)
        }
        await<Event.Connected>("the dive computer to connect; is it in Bluetooth mode?")
        onQueue { p.discoverServices(null) }
        await<Event.ServicesDiscovered>("service discovery")
        val services = onQueue { ShearwaterBle.serialServices(p.services.orEmpty().filterIsInstance<CBService>()) { it.UUID.UUIDString } }
        for (s in services) {
            onQueue { p.discoverCharacteristics(null, s) }
            await<Event.CharacteristicsDiscovered>("characteristic discovery")
        }
        val c = onQueue {
            services.asSequence()
                .flatMap { it.characteristics.orEmpty().filterIsInstance<CBCharacteristic>() }
                .firstOrNull(::isSerial)
        } ?: throw TransportException(
            "$identifier has no serial characteristic (services: ${onQueue { p.services.orEmpty().filterIsInstance<CBService>().joinToString { it.UUID.UUIDString } }})",
        )
        characteristic = c
        writeType = if (c.properties and CBCharacteristicPropertyWriteWithoutResponse != 0uL) {
            CBCharacteristicWriteWithoutResponse
        } else {
            CBCharacteristicWriteWithResponse
        }
        onQueue { p.setNotifyValue(true, c) }
        await<Event.NotifyingOrWritten>("notification setup")
    }

    /** The peripheral the system already knows by [identifier], else the first scan result with it. */
    private fun find(): CBPeripheral {
        val uuid = NSUUID(identifier)
        onQueue { central.retrievePeripheralsWithIdentifiers(listOf(uuid)).firstOrNull() as CBPeripheral? }?.let { return it }
        onQueue { central.scanForPeripheralsWithServices(null, null) }
        val deadline = TimeSource.Monotonic.markNow() + ShearwaterBle.SCAN_MS.milliseconds
        try {
            while (true) {
                val left = -deadline.elapsedNow().inWholeMilliseconds
                val found = await<Event.Discovered>("the dive computer to show up", left.coerceAtLeast(1)).peripheral
                if (found.identifier == uuid) return found
            }
        } finally {
            onQueue { central.stopScan() }
        }
    }

    override val maxWriteSize: Int?
        get() {
            val p = peripheral
            if (p == null || characteristic == null) return null
            return onQueue { p.maximumWriteValueLengthForType(writeType).toInt() }
        }

    override fun write(data: ByteArray) {
        if (closed) throw TransportClosedException()
        lost?.let { throw it }
        val p = peripheral ?: throw TransportClosedException()
        val c = characteristic ?: throw TransportClosedException()
        if (writeType == CBCharacteristicWriteWithoutResponse) {
            // Without responses CoreBluetooth drops what its buffer has no room for.
            while (!onQueue { p.canSendWriteWithoutResponse }) await<Event.ReadyToWrite>("write", WRITE_TIMEOUT_MS)
            onQueue { p.writeValue(data.toNSData(), c, writeType) }
        } else {
            onQueue { p.writeValue(data.toNSData(), c, writeType) }
            await<Event.NotifyingOrWritten>("write", WRITE_TIMEOUT_MS)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int =
        notifications.read(buffer, offset, length, timeoutMs)

    /**
     * Drops the link and waits briefly for it to go, so the device sees the session end even
     * when the process exits right after (the macOS ble-bridge does). A write without response
     * has no acknowledgement, so the last ones (the shutdown) get a moment to go out first.
     */
    override fun close() {
        val linked = characteristic != null && lost == null && !closed
        closed = true
        notifications.close()
        events.trySend(Event.Ended(TransportClosedException()))
        // CoreBluetooth takes no calls while Bluetooth is off or resetting.
        if (onQueue { central.state } != CBManagerStatePoweredOn) return
        onQueue { central.stopScan() }
        val p = peripheral ?: return
        peripheral = null
        characteristic = null
        runBlocking {
            if (linked && writeType == CBCharacteristicWriteWithoutResponse) delay(DRAIN_MS)
            onQueue { central.cancelPeripheralConnection(p) }
            if (linked) withTimeoutOrNull(DISCONNECT_TIMEOUT_MS) { disconnected.receive() }
        }
    }

    /** Run [block] on CoreBluetooth's queue; never call from that queue (the delegate). */
    private fun <T> onQueue(block: () -> T): T {
        var result: Result<T>? = null
        dispatch_sync(queue) { result = runCatching(block) }
        return requireNotNull(result) { "dispatch_sync did not run" }.getOrThrow()
    }

    /** Wait for the [T] that ends the step [what]; a failure, the link ending or [close] throws. */
    private inline fun <reified T : Event> await(what: String, timeoutMs: Long = SETUP_TIMEOUT_MS): T {
        val event = runBlocking {
            withTimeoutOrNull(timeoutMs) {
                // A stale event from an earlier step is skipped.
                var e = events.receive()
                while (e !is T && e !is Event.Failed && e !is Event.Ended) e = events.receive()
                e
            }
        } ?: throw TransportTimeoutException("Timed out waiting for $what")
        if (closed) throw TransportClosedException()
        return when (event) {
            is Event.Failed -> throw TransportException("Bluetooth $what failed: ${event.error?.localizedDescription ?: "unknown error"}")
            is Event.Ended -> throw event.reason
            else -> event as T
        }
    }

    private fun isSerial(c: CBCharacteristic): Boolean {
        val p = c.properties
        val notifies = p and (CBCharacteristicPropertyNotify or CBCharacteristicPropertyIndicate) != 0uL
        val writes = p and (CBCharacteristicPropertyWrite or CBCharacteristicPropertyWriteWithoutResponse) != 0uL
        return notifies && writes
    }

    private fun post(event: Event) {
        events.trySend(event)
    }

    private fun stepDone(error: NSError?, event: Event) = post(if (error == null) event else Event.Failed(error))

    private inner class Delegate : NSObject(), CBCentralManagerDelegateProtocol, CBPeripheralDelegateProtocol {
        override fun centralManagerDidUpdateState(central: CBCentralManager) {
            val state = central.state
            when {
                state == CBManagerStatePoweredOn -> post(Event.PoweredOn)
                // A reset drops every connection, without a disconnect callback for each.
                peripheral != null -> linkEnded(TransportException(bluetoothProblem(state) ?: "Bluetooth was reset"))
                else -> bluetoothProblem(state)?.let { linkEnded(TransportException(it)) }
            }
        }

        override fun centralManager(
            central: CBCentralManager,
            didDiscoverPeripheral: CBPeripheral,
            advertisementData: Map<Any?, *>,
            RSSI: NSNumber,
        ) = post(Event.Discovered(didDiscoverPeripheral))

        override fun centralManager(central: CBCentralManager, didConnectPeripheral: CBPeripheral) = post(Event.Connected)

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didFailToConnectPeripheral: CBPeripheral, error: NSError?) =
            post(Event.Failed(error))

        @ObjCSignatureOverride
        override fun centralManager(central: CBCentralManager, didDisconnectPeripheral: CBPeripheral, error: NSError?) {
            disconnected.trySend(Unit)
            linkEnded(TransportException("Connection to $identifier lost: ${error?.localizedDescription ?: "disconnected"}"))
        }

        override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) =
            stepDone(didDiscoverServices, Event.ServicesDiscovered)

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didDiscoverCharacteristicsForService: CBService, error: NSError?) =
            stepDone(error, Event.CharacteristicsDiscovered)

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateNotificationStateForCharacteristic: CBCharacteristic, error: NSError?) =
            stepDone(error, Event.NotifyingOrWritten)

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didWriteValueForCharacteristic: CBCharacteristic, error: NSError?) =
            stepDone(error, Event.NotifyingOrWritten)

        @ObjCSignatureOverride
        override fun peripheral(peripheral: CBPeripheral, didUpdateValueForCharacteristic: CBCharacteristic, error: NSError?) {
            didUpdateValueForCharacteristic.value?.let { notifications.offer(it.toByteArray()) }
        }

        override fun peripheralIsReadyToSendWriteWithoutResponse(peripheral: CBPeripheral) = post(Event.ReadyToWrite)

        private fun linkEnded(reason: TransportException) {
            lost = reason
            notifications.end(reason)
            post(Event.Ended(reason))
        }
    }

    private companion object {
        /** Long enough for the user to confirm a pairing dialog during setup. */
        const val SETUP_TIMEOUT_MS = 30_000L
        const val WRITE_TIMEOUT_MS = 5_000L
        const val DRAIN_MS = 300L
        const val DISCONNECT_TIMEOUT_MS = 2_000L
    }
}
