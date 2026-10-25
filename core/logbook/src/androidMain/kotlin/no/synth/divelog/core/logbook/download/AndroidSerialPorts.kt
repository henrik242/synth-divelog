package no.synth.divelog.core.logbook.download

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import android.util.Log
import kotlinx.coroutines.suspendCancellableCoroutine
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportClosedException
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.transport.BleDiveComputers
import no.synth.divelog.core.transport.BleGattTransport
import no.synth.divelog.core.transport.BluetoothDevices
import no.synth.divelog.core.transport.UsbSerialCandidate
import no.synth.divelog.core.transport.UsbSerialDevices
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android serial layer with three kinds of port: USB-serial adapters over USB host mode
 * (the wired Suunto cable), paired Bluetooth Classic SPP devices (the Petrel and
 * Predator) and Bluetooth LE Shearwaters (the Peregrine, Petrel 2). [list] returns all of them, each
 * [SerialPortInfo.id] prefixed so [open] opens the matching transport: a
 * [UsbSerialTransport] for USB, a [BluetoothRfcommTransport] for Bluetooth Classic, a
 * [BleGattTransport] for Bluetooth LE.
 *
 * USB enumeration needs no runtime permission; [open] requests the per-device USB
 * permission when it is not held, suspending on the broadcast result. Listing and
 * connecting paired Bluetooth devices needs BLUETOOTH_CONNECT, which the caller requests
 * before [list]; without it the Bluetooth ports are simply absent. LE computers come from
 * a background scan ([BleDiveComputers], needing BLUETOOTH_SCAN) that [discover] starts;
 * [open] stops it, as a scan slows down any Bluetooth connect.
 */
class AndroidSerialPorts(context: Context) : SerialPorts {
    private val appContext = context.applicationContext
    private val ble = BleDiveComputers(appContext)
    override val downloadSupported = true

    override val linkKinds: Set<SerialPortKind> =
        setOf(SerialPortKind.USB_SERIAL, SerialPortKind.BLUETOOTH_SPP, SerialPortKind.BLUETOOTH_LE)

    override fun list(): List<SerialPortInfo> = usbPorts() + bluetoothPorts() + blePorts(ble)

    override fun discover() = ble.scan()

    override fun scanProblem(): String? = ble.problem()

    // The id is the adapter's index in the current listing (open() re-lists to resolve it,
    // since the index is not stable across replugs); the descriptor keys on the stable
    // vendor/product so the connection can be remembered between sessions.
    private fun usbPorts(): List<SerialPortInfo> =
        UsbSerialDevices.available(appContext).mapIndexed { index, c ->
            SerialPortInfo(
                id = "$USB_PREFIX$index",
                label = (c.name ?: "USB serial device") + if (c.looksLikeSuuntoCable) " (Suunto cable)" else "",
                kind = SerialPortKind.USB_SERIAL,
                descriptor = "usb:${c.vendorId}:${c.productId}",
            )
        }

    // The id and descriptor are both the stable Bluetooth address; empty when
    // BLUETOOTH_CONNECT is not granted. An LE-only device has no serial port to offer.
    private fun bluetoothPorts(): List<SerialPortInfo> =
        runCatching { BluetoothDevices.paired(appContext).filter { it.raw.type != BluetoothDevice.DEVICE_TYPE_LE } }
            .getOrDefault(emptyList())
            .map { d ->
                SerialPortInfo(
                    id = "$BT_PREFIX${d.address}",
                    label = (d.name ?: d.address) + " (Bluetooth)",
                    kind = SerialPortKind.BLUETOOTH_SPP,
                    descriptor = "bt:${d.address}",
                )
            }

    override suspend fun open(id: String, params: SerialParams): Transport {
        ble.stop()
        return when {
            id.startsWith(BT_PREFIX) -> openBluetooth(id.removePrefix(BT_PREFIX))
            id.startsWith(BLE_PREFIX) -> openBle(id.removePrefix(BLE_PREFIX))
            else -> openUsb(id.removePrefix(USB_PREFIX), params)
        }
    }

    private suspend fun openUsb(index: String, params: SerialParams): Transport {
        val candidate = UsbSerialDevices.available(appContext).getOrNull(index.toIntOrNull() ?: -1)
            ?: throw TransportException("USB serial adapter not found; reconnect the cable")
        if (!UsbSerialDevices.hasPermission(appContext, candidate)) requestPermission(candidate)
        return UsbSerialDevices.transportFor(appContext, candidate, params).also { closingOnCancel(it) { it.open() } }
    }

    private suspend fun openBluetooth(address: String): Transport {
        val device = BluetoothDevices.paired(appContext).firstOrNull { it.address == address }
            ?: throw TransportException("Bluetooth device not found; pair it in system settings")
        // A Bluetooth connect can block for seconds; cancelling closes the socket to end it.
        return BluetoothDevices.transportFor(device.raw).also { closingOnCancel(it) { it.open() } }
    }

    private suspend fun openBle(address: String): Transport {
        val found = ble.found().firstOrNull { it.id == address }
        val name = found?.name ?: "the dive computer"
        val device = ble.device(address) ?: throw TransportException("Could not find $name; start Bluetooth on it and try again")
        val transport = BleGattTransport(appContext, device)
        try {
            closingOnCancel(transport) { transport.open() }
        } catch (e: TransportClosedException) {
            throw e
        } catch (e: TransportException) {
            // The GATT detail (status codes, the address) goes to the log; the user gets what to do.
            Log.w(TAG, "Bluetooth LE connect to $address failed", e)
            throw TransportException("Could not connect to $name. Start Bluetooth on it, keep it near the phone and try again.", e)
        }
        return transport
    }

    /** Ask for USB permission and suspend until the broadcast grants or denies it. */
    private suspend fun requestPermission(candidate: UsbSerialCandidate): Unit =
        suspendCancellableCoroutine { cont ->
            val action = "$ACTION_USB_PERMISSION.${appContext.packageName}"
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) {
                    if (intent.action != action) return
                    runCatching { appContext.unregisterReceiver(this) }
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        cont.resume(Unit)
                    } else {
                        cont.resumeWithException(TransportException("USB permission denied"))
                    }
                }
            }
            // minSdk 33: the receiver is app-internal, so it must be registered not-exported.
            appContext.registerReceiver(receiver, IntentFilter(action), Context.RECEIVER_NOT_EXPORTED)
            cont.invokeOnCancellation { runCatching { appContext.unregisterReceiver(receiver) } }
            UsbSerialDevices.requestPermission(appContext, candidate, action)
        }

    private companion object {
        const val ACTION_USB_PERMISSION = "no.synth.divelog.USB_PERMISSION"
        const val USB_PREFIX = "usb:"
        const val BT_PREFIX = "bt:"
        const val TAG = "SynthDivelog"
    }
}
