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
private class BleDevice(val name: String?, val address: String, val raw: BluetoothDevice)

/**
 * Android's [BleScanner]: [found] is what scans have seen plus bonded Peregrines, each by
 * its Bluetooth address, and [device] the Android device to connect to.
 *
 * Scanning needs BLUETOOTH_SCAN (declared neverForLocation) and listing bonded devices
 * BLUETOOTH_CONNECT; without them, or with Bluetooth off, the list is empty and
 * [problem] says why.
 */
class BleDiveComputers(context: Context) : BleScanner {
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
            if (ShearwaterBle.isPeregrine(name, advertisesService)) seen[d.address] = BleDevice(name, d.address, d)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
        }
    }

    override fun found(): List<ScannedDevice> {
        val all = LinkedHashMap<String, ScannedDevice>()
        (bonded() + seen.values).forEach { all[it.address] = ScannedDevice(it.address, it.name) }
        return byName(all.values)
    }

    /** The device at [address] from a scan or bonding, with the address type Android saw. */
    fun device(address: String): BluetoothDevice? =
        seen[address]?.raw ?: bonded().firstOrNull { it.address == address }?.raw

    override fun problem(): String? {
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
    override fun scan() {
        if (scanning) return
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        // Unfiltered: a filter on the service would miss a computer that only advertises its name.
        val started = runCatching {
            val scanner = adapter()?.takeIf { it.isEnabled }?.bluetoothLeScanner ?: return
            scanner.startScan(null, settings, callback)
        }.isSuccess
        if (!started) return
        scanning = true
        main.postDelayed({ stop() }, STOP_TOKEN, ShearwaterBle.SCAN_MS)
    }

    /** Also drops the pending timeout, so a later [scan] runs its full time. */
    @SuppressLint("MissingPermission")
    override fun stop() {
        main.removeCallbacksAndMessages(STOP_TOKEN)
        if (!scanning) return
        scanning = false
        runCatching { adapter()?.bluetoothLeScanner?.stopScan(callback) }
    }

    @SuppressLint("MissingPermission")
    private fun bonded(): List<BleDevice> = runCatching {
        adapter()?.bondedDevices.orEmpty()
            .filter { it.type == BluetoothDevice.DEVICE_TYPE_LE || it.type == BluetoothDevice.DEVICE_TYPE_DUAL }
            .filter { ShearwaterBle.isPeregrineName(it.name) }
            .map { BleDevice(it.name, it.address, it) }
    }.getOrDefault(emptyList())

    private fun adapter() = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    companion object {
        private val STOP_TOKEN = Any()
        private val SERVICE = ParcelUuid.fromString(ShearwaterBle.SERVICE_UUID)
    }
}
