package no.synth.divelog.core.transport

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context

/** A paired Bluetooth device the user can download from. */
data class PairedDevice(
    val name: String?,
    val address: String,
    val raw: BluetoothDevice,
)

object BluetoothDevices {
    /**
     * Devices already paired in system settings. The Predator and Petrel 1 pair
     * as Bluetooth Classic serial devices, so pairing is done in system settings
     * first. Requires BLUETOOTH_CONNECT on current Android versions.
     */
    @SuppressLint("MissingPermission")
    fun paired(context: Context): List<PairedDevice> {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return emptyList()
        return adapter.bondedDevices.orEmpty()
            .map { PairedDevice(name = it.name, address = it.address, raw = it) }
            .sortedBy { it.name ?: it.address }
    }

    fun transportFor(device: BluetoothDevice): BluetoothRfcommTransport =
        BluetoothRfcommTransport(device)
}
