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
import no.synth.divelog.core.transport.UsbSerialCandidate
import no.synth.divelog.core.transport.UsbSerialDevices
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Android serial layer over USB host mode: lists USB-serial adapters and opens one as a
 * transport, requesting the runtime USB device permission first when it is not held. The
 * permission result arrives as a broadcast, so [open] suspends until it resolves. Needs a
 * USB-OTG adapter on the phone; enumeration and the transport reuse [UsbSerialDevices].
 */
class AndroidSerialPorts(context: Context) : SerialPorts {
    private val appContext = context.applicationContext
    override val downloadSupported = true

    // The id is the adapter's index in the current listing; open() re-lists to resolve it.
    override fun list(): List<SerialPortInfo> =
        UsbSerialDevices.available(appContext).mapIndexed { index, c ->
            SerialPortInfo(
                id = index.toString(),
                label = (c.name ?: "USB serial device") + if (c.looksLikeSuuntoCable) " (Suunto cable)" else "",
            )
        }

    override suspend fun open(id: String, params: SerialParams): Transport {
        val candidate = UsbSerialDevices.available(appContext).getOrNull(id.toIntOrNull() ?: -1)
            ?: throw TransportException("USB serial adapter not found; reconnect the cable")
        if (!UsbSerialDevices.hasPermission(appContext, candidate)) requestPermission(candidate)
        return UsbSerialDevices.transportFor(appContext, candidate, params).also { it.open() }
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
    }
}
