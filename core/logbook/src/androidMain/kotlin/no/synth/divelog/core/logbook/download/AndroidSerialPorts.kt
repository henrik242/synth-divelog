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
 * Predator, and the newer ones) and Bluetooth LE Shearwaters. [list] returns all of them, each
 * [SerialPortInfo.id] prefixed so [transport] makes the matching one: a
 * [UsbSerialTransport] for USB, a [BluetoothRfcommTransport] for Bluetooth Classic, a
 * [BleGattTransport] for Bluetooth LE.
 *
 * USB enumeration needs no runtime permission; [transport] requests the per-device USB
 * permission when it is not held, suspending on the broadcast result. Listing and
 * connecting paired Bluetooth devices needs BLUETOOTH_CONNECT, which the caller requests
 * before [list]; without it the Bluetooth ports are simply absent. LE computers come from
 * a background scan ([BleDiveComputers], needing BLUETOOTH_SCAN).
 */
class AndroidSerialPorts(context: Context) : SerialPorts {
    private val appContext = context.applicationContext
    override val bleScanner = BleDiveComputers(appContext)
    override val downloadSupported = true

    override fun list(): List<SerialPortInfo> = usbPorts() + bluetoothPorts() + blePorts(bleScanner.found())

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

    override suspend fun transport(id: String, params: SerialParams): Transport = when {
        id.startsWith(BT_PREFIX) -> bluetoothTransport(id.removePrefix(BT_PREFIX))
        id.startsWith(BLE_PREFIX) -> bleTransport(id.removePrefix(BLE_PREFIX))
        else -> usbTransport(id.removePrefix(USB_PREFIX), params)
    }

    private suspend fun usbTransport(index: String, params: SerialParams): Transport {
        val candidate = UsbSerialDevices.available(appContext).getOrNull(index.toIntOrNull() ?: -1)
            ?: throw TransportException("USB serial adapter not found; reconnect the cable")
        if (!UsbSerialDevices.hasPermission(appContext, candidate)) requestPermission(candidate)
        return UsbSerialDevices.transportFor(appContext, candidate, params)
    }

    private fun bluetoothTransport(address: String): Transport {
        val device = BluetoothDevices.paired(appContext).firstOrNull { it.address == address }
            ?: throw TransportException("Bluetooth device not found; pair it in system settings")
        return BluetoothDevices.transportFor(device.raw)
    }

    /** A GATT link whose failure to connect says what to do; the GATT detail (status codes, the address) goes to the log. */
    private fun bleTransport(address: String): Transport {
        val name = bleScanner.found().firstOrNull { it.id == address }?.name ?: "the dive computer"
        val device = bleScanner.device(address) ?: throw TransportException("Could not find $name; start Bluetooth on it and try again")
        val gatt = BleGattTransport(appContext, device)
        return object : Transport by gatt {
            override fun open() = try {
                gatt.open()
            } catch (e: TransportClosedException) {
                throw e
            } catch (e: TransportException) {
                Log.w(TAG, "Bluetooth LE connect to $address failed", e)
                throw TransportException("Could not connect to $name. Start Bluetooth on it, keep it near the phone and try again.", e)
            }
        }
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
