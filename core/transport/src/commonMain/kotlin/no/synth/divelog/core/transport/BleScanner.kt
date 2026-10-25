package no.synth.divelog.core.transport

/** A Bluetooth LE dive computer a scan found: the [id] to connect to it by, and its advertised [name]. */
data class ScannedDevice(val id: String, val name: String?)

/**
 * Finds Bluetooth LE dive computers in the background, so the picker never blocks on a
 * scan: Android's BleDiveComputers, CoreBluetoothScanner (iOS) and MacBleScanner (the
 * desktop on macOS). Which devices count is [ShearwaterBle.isPeregrine].
 */
interface BleScanner {
    /** Scan for [ShearwaterBle.SCAN_MS] unless a scan is running; the picker calls it again while it waits. */
    fun scan()

    /** End a scan in progress; a scan slows down a connect. */
    fun stop()

    /** What scans have seen, by name. */
    fun found(): List<ScannedDevice>

    /** Why a scan cannot find anything right now, worded for the picker, or null. */
    fun problem(): String?
}

/** [devices] in the picker's order: by name, nameless ones by id. */
internal fun byName(devices: Collection<ScannedDevice>): List<ScannedDevice> = devices.sortedBy { it.name ?: it.id }
