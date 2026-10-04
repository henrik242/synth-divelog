package no.synth.divelog.core.transport

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.TransportException

/** A USB serial adapter found on the bus that a dive computer can be read through. */
data class UsbSerialCandidate(
    val name: String?,
    val vendorId: Int,
    val productId: Int,
    val driver: UsbSerialDriver,
) {
    /** FTDI and Prolific are the chips in Suunto's old interface cables. */
    val looksLikeSuuntoCable: Boolean
        get() = (vendorId == FTDI_VID && productId == FTDI_PID) || vendorId == PROLIFIC_VID

    private companion object {
        const val FTDI_VID = 0x0403
        const val FTDI_PID = 0x6001
        const val PROLIFIC_VID = 0x067B
    }
}

/**
 * Enumerates USB serial adapters and opens one as a [UsbSerialTransport]. The USB
 * host feature and a runtime device permission are the caller's responsibility; use
 * [hasPermission] and [requestPermission] before [transportFor]. Requires a USB-OTG
 * adapter on the phone.
 */
object UsbSerialDevices {
    fun usbManager(context: Context): UsbManager =
        context.getSystemService(Context.USB_SERVICE) as UsbManager

    /** USB serial adapters currently attached. */
    fun available(context: Context): List<UsbSerialCandidate> {
        val manager = usbManager(context)
        return UsbSerialProber.getDefaultProber().findAllDrivers(manager).map { driver ->
            val device = driver.device
            UsbSerialCandidate(
                name = device.productName ?: device.deviceName,
                vendorId = device.vendorId,
                productId = device.productId,
                driver = driver,
            )
        }
    }

    fun hasPermission(context: Context, candidate: UsbSerialCandidate): Boolean =
        usbManager(context).hasPermission(candidate.driver.device)

    /**
     * Ask the system for permission to use [candidate]. The result arrives as a
     * broadcast with [action]; the caller registers the receiver and reads
     * [UsbManager.EXTRA_PERMISSION_GRANTED].
     */
    fun requestPermission(context: Context, candidate: UsbSerialCandidate, action: String) {
        val intent = PendingIntent.getBroadcast(
            context,
            0,
            Intent(action).setPackage(context.packageName),
            PendingIntent.FLAG_IMMUTABLE,
        )
        usbManager(context).requestPermission(candidate.driver.device, intent)
    }

    /**
     * Open [candidate] with [params] as a [UsbSerialTransport]. Call [UsbSerialTransport.open]
     * afterwards. Throws if permission was not granted or the device cannot be opened.
     */
    fun transportFor(
        context: Context,
        candidate: UsbSerialCandidate,
        params: SerialParams,
    ): UsbSerialTransport {
        val manager = usbManager(context)
        val connection = manager.openDevice(candidate.driver.device)
            ?: throw TransportException("Could not open USB device (permission not granted?)")
        val port = candidate.driver.ports.firstOrNull()
            ?: throw TransportException("USB serial driver exposes no ports")
        return UsbSerialTransport(connection, port, params)
    }
}
