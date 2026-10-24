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
 * write and a [read] with room for it returns one whole notification, which the
 * protocol's own packet layer (ShearwaterBlePackets) relies on.
 *
 * [open] connects (retrying once on Android's common first-connect error 133), asks for
 * a larger MTU (keeping the default 23 if that is refused; [maxWriteSize] tells the
 * packet layer what fits), finds the serial service and characteristic, and turns on
 * notifications. Android serves one GATT operation at a time, so each step waits for its
 * callback. Notifications queue on the binder thread and [read] waits on that queue with
 * a deadline. Whether the Peregrine needs bonding is unverified; if a device does,
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
    private val notifications = LinkedBlockingQueue<ByteArray>()
    private val results = LinkedBlockingQueue<Result>()
    private var leftover = END
    private var leftoverPos = 0

    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var characteristic: BluetoothGattCharacteristic? = null
    @Volatile private var closed = false
    @Volatile private var lostStatus: Int? = null
    @Volatile private var mtu: Int? = null
    private var writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT

    /** The outcome of one GATT step, posted by the callback that ends it. */
    private sealed interface Result {
        val status: Int

        data class Connected(override val status: Int) : Result
        data class Mtu(val mtu: Int, override val status: Int) : Result
        data class Discovered(override val status: Int) : Result
        data class DescriptorWritten(override val status: Int) : Result
        data class Written(override val status: Int) : Result
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> results.put(Result.Connected(status))
                BluetoothProfile.STATE_DISCONNECTED -> {
                    lostStatus = status
                    // Wake whichever wait is pending: a setup step or a read.
                    results.put(Result.Connected(DISCONNECTED))
                    notifications.put(END)
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            results.put(Result.Mtu(mtu, status))
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            results.put(Result.Discovered(status))
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            results.put(Result.DescriptorWritten(status))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            results.put(Result.Written(status))
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            if (value.isNotEmpty()) notifications.put(value.copyOf())
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
        lostStatus = null
        results.clear()
        notifications.clear()
        val g = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            ?: throw TransportException("Could not connect to ${device.address}")
        gatt = g
        if (closed) throw TransportClosedException() // closed while connectGatt ran
        await<Result.Connected>("connect")
        mtu = negotiateMtu(g)
        if (!g.discoverServices()) throw TransportException("Service discovery refused")
        await<Result.Discovered>("service discovery")
        val c = serialCharacteristic(g.services.orEmpty())
            ?: throw TransportException("${device.address} has no serial characteristic")
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
        val result = await<Result.Mtu>("MTU exchange", checkStatus = false)
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
        await<Result.DescriptorWritten>("notification setup")
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
        if (stale.any { it.status == DISCONNECTED }) throw lost("write")
        val started = g.writeCharacteristic(c, data, writeType)
        if (started != BluetoothStatusCodes.SUCCESS) throw TransportException("Write to ${device.address} refused ($started)")
        await<Result.Written>("write", WRITE_TIMEOUT_MS)
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int, timeoutMs: Long): Int {
        if (closed) throw TransportClosedException()
        if (leftoverPos >= leftover.size) {
            val packet = notifications.poll(timeoutMs.coerceAtLeast(1), TimeUnit.MILLISECONDS)
                ?: throw TransportTimeoutException()
            if (packet.isEmpty()) {
                notifications.put(END) // later reads see the end too
                if (closed) throw TransportClosedException()
                throw TransportException("Connection to ${device.address} lost (status ${lostStatus ?: 0})")
            }
            leftover = packet
            leftoverPos = 0
        }
        // A caller reading less than a whole packet gets the rest on its next read.
        val n = minOf(length, leftover.size - leftoverPos)
        leftover.copyInto(buffer, offset, leftoverPos, leftoverPos + n)
        leftoverPos += n
        return n
    }

    override fun close() {
        closed = true
        notifications.put(END)
        results.put(Result.Connected(DISCONNECTED))
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
    private inline fun <reified T : Result> await(
        what: String,
        timeoutMs: Long = SETUP_TIMEOUT_MS,
        checkStatus: Boolean = true,
    ): T {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            val result = (if (left > 0) results.poll(left, TimeUnit.MILLISECONDS) else null)
                ?: throw TransportTimeoutException("Timed out waiting for Bluetooth $what")
            if (closed) throw TransportClosedException()
            if (result.status == DISCONNECTED) throw lost(what)
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
        /**
         * Shearwater's serial service, as Subsurface lists it; not yet confirmed on a
         * device, so [serialCharacteristic] also falls back to any vendor service.
         */
        val SHEARWATER_SERVICE: UUID = UUID.fromString("fe25c237-0ece-443c-b0aa-e02033e7029d")

        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private val BASE_UUID: UUID = UUID.fromString("00000000-0000-1000-8000-00805f9b34fb")

        private const val REQUESTED_MTU = 247
        private const val DEFAULT_MTU = 23
        private const val ATT_OVERHEAD = 3
        private const val DISCONNECTED = -1

        /** Android's catch-all GATT_ERROR, often seen on a first connect. */
        private const val GATT_ERROR = 133
        private const val RETRY_DELAY_MS = 500L

        /** Long enough for the user to confirm a pairing dialog during setup. */
        private const val SETUP_TIMEOUT_MS = 30_000L
        private const val WRITE_TIMEOUT_MS = 5_000L

        /** Queued when the link ends; notifications are never empty. */
        private val END = ByteArray(0)

        /**
         * The characteristic a dive computer's serial link rides: in Shearwater's service if
         * present, else the first non-standard service with one that both notifies (or
         * indicates) and takes writes.
         */
        internal fun serialCharacteristic(services: List<BluetoothGattService>): BluetoothGattCharacteristic? {
            val ordered = services.filter { it.uuid == SHEARWATER_SERVICE } +
                services.filter { it.uuid != SHEARWATER_SERVICE && !isStandard(it.uuid) }
            return ordered.asSequence().flatMap { it.characteristics.orEmpty().asSequence() }.firstOrNull(::isSerial)
        }

        private fun isSerial(c: BluetoothGattCharacteristic): Boolean {
            val p = c.properties
            val notifies = p and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
            val writes = p and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0
            return notifies && writes
        }

        /** A Bluetooth SIG service (0000xxxx-0000-1000-8000-00805f9b34fb): GAP, GATT, battery, device info. */
        private fun isStandard(uuid: UUID): Boolean =
            uuid.leastSignificantBits == BASE_UUID.leastSignificantBits &&
                (uuid.mostSignificantBits and 0xFFFFFFFFL) == (BASE_UUID.mostSignificantBits and 0xFFFFFFFFL)
    }
}
