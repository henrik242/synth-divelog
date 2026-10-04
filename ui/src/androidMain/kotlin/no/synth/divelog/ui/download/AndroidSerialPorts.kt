package no.synth.divelog.ui.download

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbManager
import kotlinx.coroutines.suspendCancellableCoroutine
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.TransportException
import no.synth.divelog.core.transport.BluetoothDevices
import no.synth.divelog.core.transport.UsbSerialCandidate
import no.synth.divelog.core.transport.UsbSerialDevices
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android serial layer with two kinds of port: USB-serial adapters over USB host mode
 * (the wired Suunto cable) and paired Bluetooth Classic SPP devices (the Shearwater
 * computers). [list] returns both, each [SerialPortInfo.id] prefixed so [open] opens the
 * matching transport: a [UsbSerialTransport] for USB, a [BluetoothRfcommTransport] for
 * Bluetooth.
 *
 * USB enumeration needs no runtime permission; [open] requests the per-device USB
 * permission when it is not held, suspending on the broadcast result. Listing and
 * connecting paired Bluetooth devices needs BLUETOOTH_CONNECT, which the caller requests
 * before [list]; without it the Bluetooth ports are simply absent.
 */
class AndroidSerialPorts(context: Context) : SerialPorts {
    private val appContext = context.applicationContext
    override val downloadSupported = true

    override fun list(): List<SerialPortInfo> = usbPorts() + bluetoothPorts()

    // The id is the adapter's index in the current listing; open() re-lists to resolve it.
    private fun usbPorts(): List<SerialPortInfo> =
        UsbSerialDevices.available(appContext).mapIndexed { index, c ->
            SerialPortInfo(
                id = "$USB_PREFIX$index",
                label = (c.name ?: "USB serial device") + if (c.looksLikeSuuntoCable) " (Suunto cable)" else "",
                kind = SerialPortKind.USB_SERIAL,
            )
        }

    // The id is the stable Bluetooth address; empty when BLUETOOTH_CONNECT is not granted.
    private fun bluetoothPorts(): List<SerialPortInfo> =
        runCatching { BluetoothDevices.paired(appContext) }.getOrDefault(emptyList()).map { d ->
            SerialPortInfo(
                id = "$BT_PREFIX${d.address}",
                label = (d.name ?: d.address) + " (Bluetooth)",
                kind = SerialPortKind.BLUETOOTH_SPP,
            )
        }

    override suspend fun open(id: String, params: SerialParams): Transport =
        if (id.startsWith(BT_PREFIX)) {
            openBluetooth(id.removePrefix(BT_PREFIX))
        } else {
            openUsb(id.removePrefix(USB_PREFIX), params)
        }

    private suspend fun openUsb(index: String, params: SerialParams): Transport {
        val candidate = UsbSerialDevices.available(appContext).getOrNull(index.toIntOrNull() ?: -1)
            ?: throw TransportException("USB serial adapter not found; reconnect the cable")
        if (!UsbSerialDevices.hasPermission(appContext, candidate)) requestPermission(candidate)
        return UsbSerialDevices.transportFor(appContext, candidate, params).also { it.open() }
    }

    private fun openBluetooth(address: String): Transport {
        val device = BluetoothDevices.paired(appContext).firstOrNull { it.address == address }
            ?: throw TransportException("Bluetooth device not found; pair it in system settings")
        return BluetoothDevices.transportFor(device.raw).also { it.open() }
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
    }
}
