package no.synth.divelog.core.transport

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.concurrent.ConcurrentHashMap

/** A Bluetooth LE dive computer seen in a scan or bonded in system settings. */
data class BleDevice(
    val name: String?,
    val address: String,
    val raw: BluetoothDevice,
)

/**
 * Finds a Shearwater Peregrine over Bluetooth LE. A scan cannot block the picker, so
 * [scan] runs one in the background for [SCAN_MS] (the picker calls it again while it
 * waits) and [found] returns what it has seen so far plus bonded Peregrines, by name. A
 * device counts if it carries a Peregrine name, or advertises Shearwater's service with
 * no name; other Shearwater models are left out until their own computer type exists.
 *
 * Scanning needs BLUETOOTH_SCAN (declared neverForLocation) and listing bonded devices
 * BLUETOOTH_CONNECT; without them, or with Bluetooth off, the list is empty and
 * [problem] says why.
 */
class BleDiveComputers(context: Context) {
    private val appContext = context.applicationContext
    private val seen = ConcurrentHashMap<String, BleDevice>()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var scanning = false

    private val callback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val d = result.device ?: return
            val name = result.scanRecord?.deviceName ?: runCatching { d.name }.getOrNull()
            val advertisesService = result.scanRecord?.serviceUuids.orEmpty().contains(SERVICE)
            // Other Shearwaters may advertise the same service, so a named one must be a Peregrine.
            if (isPeregrineName(name) || (advertisesService && name == null)) seen[d.address] = BleDevice(name, d.address, d)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
        }
    }

    /** Devices seen so far plus bonded Peregrines, by name. */
    fun found(): List<BleDevice> {
        val all = LinkedHashMap<String, BleDevice>()
        bonded().forEach { all[it.address] = it }
        seen.values.forEach { all[it.address] = it }
        return all.values.sortedBy { it.name ?: it.address }
    }

    /** The device at [address] from a scan or bonding, with the address type Android saw. */
    fun device(address: String): BluetoothDevice? =
        seen[address]?.raw ?: bonded().firstOrNull { it.address == address }?.raw

    /** Why a scan cannot find anything right now, worded for the picker, or null. */
    fun problem(): String? {
        val adapter = adapter() ?: return "This phone has no Bluetooth"
        val granted = listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            .all { appContext.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }
        return when {
            !granted -> "Allow Nearby devices for this app in Android settings to find the computer"
            !adapter.isEnabled -> "Turn on Bluetooth on the phone"
            else -> null
        }
    }

    /**
     * Start a background scan unless one is running. A refused scan (no permission, Bluetooth
     * off) is ignored, so the USB and Bluetooth Classic ports still list.
     */
    @SuppressLint("MissingPermission")
    fun scan() {
        if (scanning) return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // Unfiltered: a filter on the service would miss a computer that only advertises its name.
        val started = runCatching {
            val scanner = adapter()?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
            scanner.startScan(null, settings, callback)
        }.isSuccess
        if (!started) return
        scanning = true
        main.postDelayed({ stop() }, STOP_TOKEN, SCAN_MS)
    }

    /** End a scan in progress, and its pending timeout, so a later [scan] runs its full time. */
    @SuppressLint("MissingPermission")
    fun stop() {
        main.removeCallbacksAndMessages(STOP_TOKEN)
        if (!scanning) return
        scanning = false
        runCatching { adapter()?.bluetoothLeScanner?.stopScan(callback) }
    }

    @SuppressLint("MissingPermission")
    private fun bonded(): List<BleDevice> = runCatching {
        adapter()?.bondedDevices.orEmpty()
            .filter { it.type == BluetoothDevice.DEVICE_TYPE_LE || it.type == BluetoothDevice.DEVICE_TYPE_DUAL }
            .filter { isPeregrineName(it.name) }
            .map { BleDevice(it.name, it.address, it) }
    }.getOrDefault(emptyList())

    private fun adapter() = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    companion object {
        private const val SCAN_MS = 20_000L
        private val STOP_TOKEN = Any()
        private val SERVICE = ParcelUuid(BleGattTransport.SHEARWATER_SERVICE)

        /** Whether [name] is one a Peregrine advertises ("Peregrine", "Peregrine TX", ...). */
        fun isPeregrineName(name: String?): Boolean = name != null && name.contains("Peregrine", ignoreCase = true)
    }
}
