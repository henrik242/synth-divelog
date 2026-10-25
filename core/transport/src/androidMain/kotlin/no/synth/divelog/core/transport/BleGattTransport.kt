package no.synth.divelog.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.divecomputer.transport.TransportTimeoutException
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * A Bluetooth LE dive computer reached through one GATT characteristic: writes go to it
 * and its notifications come back. Packet boundaries are kept: one [write] is one GATT
 * write and a read returns bytes of one notification only ([PacketQueue]).
 *
 * [open] connects (retrying once on Android's common first-connect error 133), asks for
 * a larger MTU (keeping the default 23 if that is refused; [maxWriteSize] tells the
 * packet layer what fits), finds the serial service and characteristic, and turns on
 * notifications. Android serves one GATT operation at a time, so each step waits for its
 * callback. Whether the Peregrine needs bonding is unverified; if a device does,
 * Android starts it when the notification setup is refused and shows the system pairing
 * dialog. [close] from another thread ends a wait in progress, and a transport closed
 * before [open] never connects.
 *
 * Requires the BLUETOOTH_CONNECT runtime permission (granted by the caller).
 */
class BleGattTransport(
    private val context: Context,
    private val device: BluetoothDevice,
) : Transport {
    private val notifications = PacketQueue()
    private val results = LinkedBlockingQueue<Result>()

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var characteristic: BluetoothGattCharacteristic? = null
    @Volatile private var closed = false
    @Volatile private var lostStatus: Int? = null
    @Volatile private var mtu: Int? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    /** What a wait can get: the end of a GATT step, or of the link. */
    private sealed interface Result {
        /** The link dropped or was closed; ends any wait. */
        data object Ended : Result
    }

    /** The outcome of one GATT step, posted by the callback that ends it. */
    private sealed interface Step : Result {
        val status: Int

        data class Connected(override val status: Int) : Step
        data class Mtu(val mtu: Int, override val status: Int) : Step
        data class Discovered(override val status: Int) : Step
        data class DescriptorWritten(override val status: Int) : Step
        data class Written(override val status: Int) : Step
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> results.put(Step.Connected(status))
                BluetoothProfile.STATE_DISCONNECTED -> {
                    lostStatus = status
                    // Wake whichever wait is pending: a setup step or a read.
                    results.put(Result.Ended)
                    notifications.end(TransportException("Connection to ${device.address} lost (status $status)"))
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            results.put(Step.Mtu(mtu, status))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            results.put(Step.Discovered(status))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            results.put(Step.DescriptorWritten(status))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            results.put(Step.Written(status))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            notifications.offer(value)
        }
    }

    override fun open() {
        if (closed) throw TransportClosedException()
        try {
            try {
                connect()
            } catch (e: TransportException) {
                if (closed || lostStatus != GATT_ERROR) throw e
                // Status 133 on a first connect is often transient; one fresh attempt.
                release()
                Thread.sleep(RETRY_DELAY_MS)
                connect()
            }
        } catch (e: Exception) {
            close()
            if (e is TransportException) throw e
            throw TransportException("Could not connect to ${device.address}", e)
        }
    }

    // The connection-settings connectGatt that replaces this one needs API 37; minSdk is 33.
    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun connect() {
        if (closed) throw TransportClosedException() // closed during the retry delay
        lostStatus = null
        results.clear()
        notifications.clear()
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw TransportException("Could not connect to ${device.address}")
        gatt = g
        if (closed) throw TransportClosedException() // closed while connectGatt ran
        await<Step.Connected>("connect")
        mtu = negotiateMtu(g)
        if (!g.discoverServices()) throw TransportException("Service discovery refused")
        await<Step.Discovered>("service discovery")
        val c = serialCharacteristic(g.services.orEmpty())
            ?: throw TransportException("${device.address} has no serial characteristic (services: ${g.services.orEmpty().joinToString { it.uuid.toString() }})")
        characteristic = c
        writeType = if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
        } else {
            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        }
        enableNotifications(g, c)
        // A shorter connection interval speeds up the many small exchanges of a download.
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
    }

    /** The agreed MTU, or the default 23 when the device refuses a larger one. */
    @SuppressLint("MissingPermission")
    private fun negotiateMtu(g: BluetoothGatt): Int {
        if (!g.requestMtu(REQUESTED_MTU)) return DEFAULT_MTU
        val result = await<Step.Mtu>("MTU exchange", checkStatus = false)
        return if (result.status == BluetoothGatt.GATT_SUCCESS) result.mtu else DEFAULT_MTU
    }

    @SuppressLint("MissingPermission")
    private fun enableNotifications(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
        if (!g.setCharacteristicNotification(c, true)) throw TransportException("Notifications refused")
        val cccd = c.getDescriptor(CCCD_UUID) ?: return // notifications flow without a client config
        val value = if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
        } else {
            BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
        }
        val started = g.writeDescriptor(cccd, value)
        if (started != BluetoothStatusCodes.SUCCESS) throw TransportException("Notification setup refused ($started)")
        await<Step.DescriptorWritten>("notification setup")
    }

    override val maxWriteSize: Int? get() = mtu?.let { it - ATT_OVERHEAD }

    @SuppressLint("MissingPermission")
    override fun write(data: ByteArray) {
        if (closed) throw TransportClosedException()
        val g = gatt ?: throw TransportClosedException()
        val c = characteristic ?: throw TransportClosedException()
        // Drop a late callback from a write that timed out, so it cannot end this one.
        val stale = ArrayList<Result>()
        results.drainTo(stale)
        if (Result.Ended in stale) throw lost("write")
        val started = g.writeCharacteristic(c, data, writeType)
        if (started != BluetoothStatusCodes.SUCCESS) throw TransportException("Write to ${device.address} refused ($started)")
        await<Step.Written>("write", WRITE_TIMEOUT_MS)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int =
        notifications.read(buffer, offset, length, timeoutMs)

    override fun close() {
        closed = true
        notifications.close()
        results.put(Result.Ended)
        release()
    }

    /** Disconnect and free the GATT client, if any; [close] also wakes the waits. */
    @SuppressLint("MissingPermission")
    private fun release() {
        val g = gatt
        gatt = null
        characteristic = null
        if (g != null) {
            runCatching { g.disconnect() }
            runCatching { g.close() }
        }
    }

    /**
     * Wait for the callback that ends the step [what]; a disconnect or close ends it with an
     * error, and so does a failed status unless [checkStatus] is off.
     */
    private inline fun <reified T : Step> await(
        what: String,
        timeoutMs: Long = SETUP_TIMEOUT_MS,
        checkStatus: Boolean = true,
    ): T {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val left = deadline - System.nanoTime()
            val result = (if (left > 0) results.poll(left, TimeUnit.NANOSECONDS) else null)
                ?: throw TransportTimeoutException("Timed out waiting for Bluetooth $what")
            if (closed) throw TransportClosedException()
            if (result == Result.Ended) throw lost(what)
            // A stale result from an earlier step is skipped.
            if (result !is T) continue
            if (checkStatus && result.status != BluetoothGatt.GATT_SUCCESS) {
                throw TransportException("Bluetooth $what failed (status ${result.status})")
            }
            return result
        }
    }

    private fun lost(what: String) =
        TransportException("${device.address} disconnected during $what (status ${lostStatus ?: 0})")

    companion object {
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** From Android 14 the system asks for 517 whatever is passed; the agreed MTU comes from onMtuChanged. */
        private const val REQUESTED_MTU = 247
        private const val DEFAULT_MTU = 23
        private const val ATT_OVERHEAD = 3

        /** Android's catch-all GATT_ERROR, often seen on a first connect. */
        private const val GATT_ERROR = 133
        private const val RETRY_DELAY_MS = 500L

        /** Long enough for the user to confirm a pairing dialog during setup. */
        private const val SETUP_TIMEOUT_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 5_000L

        /** The characteristic the serial link rides, searched in [ShearwaterBle.serialServices] order. */
        internal fun serialCharacteristic(services: List<BluetoothGattService>): BluetoothGattCharacteristic? =
            ShearwaterBle.serialServices(services) { it.uuid.toString() }
                .asSequence().flatMap { it.characteristics.orEmpty().asSequence() }.firstOrNull(::isSerial)

        private fun isSerial(c: BluetoothGattCharacteristic): Boolean {
            val p = c.properties
            val notifies = p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            val writes = p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            return notifies && writes
        }
    }
}
