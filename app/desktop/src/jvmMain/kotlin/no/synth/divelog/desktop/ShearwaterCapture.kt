package no.synth.divelog.desktop

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerKind
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.transport.MacBleScanner
import no.synth.divelog.core.transport.MacBleTransport
import no.synth.divelog.core.transport.MacRfcommTransport
import no.synth.divelog.core.transport.ScannedDevice
import java.time.Instant

/**
 * Command-line Shearwater download over the macOS Bluetooth bridges, to test the link
 * and protocol without the app. A Petrel 1 or Predator is picked from the paired
 * serial-port devices; a newer Shearwater (Petrel 2 and 3, Perdix, NERD, Teric, Peregrine,
 * Tern) from a Bluetooth LE scan, or with CLASSIC from the paired devices. It then
 * downloads the latest dives and prints them parsed.
 *
 * Run: ./gradlew :app:desktop:shearwaterCapture --args="[PETREL1|PREDATOR|CLASSIC] [name or id] [dive count]"
 * Defaults: a newer Shearwater over Bluetooth LE, the device whose name says it is one, 3 dives.
 */
fun main(args: Array<String>) {
    val words = args.map { it.uppercase() }
    val kind = KINDS.entries.firstOrNull { it.key in words }?.value ?: DiveComputerKind.SHEARWATER
    val count = args.firstNotNullOfOrNull { it.toIntOrNull() } ?: 3
    val target = args.zip(words).firstOrNull { (_, w) -> w !in KINDS && w != CLASSIC && w.toIntOrNull() == null }?.first
    val overLe = kind == DiveComputerKind.SHEARWATER && CLASSIC !in words
    val transport = when {
        overLe -> bleTransport(target, kind)
        else -> sppTransport(target, kind)
    } ?: return

    val start = System.currentTimeMillis()
    fun elapsed() = "%.1fs".format((System.currentTimeMillis() - start) / 1000.0)
    println("Connecting as ${kind.displayName}; put it in download mode")
    try {
        transport.open()
        println("Connected after ${elapsed()}")
        val protocol = kind.protocol(transport)
        val listener = object : DownloadListener {
            override fun onDeviceInfo(info: DeviceInfo) = println("Device: ${info.vendor} ${info.model}")
            override fun onDiveCount(total: Int) = println("Downloading $total dive(s)")
            override fun onDiveDownloaded(index: Int, dive: RawDive) = println("  dive ${index + 1} read, ${elapsed()}")
        }
        val raws = protocol.download(null, listener, CancellationSignal.NONE, limit = count)
        println("Downloaded ${raws.size} raw dive(s) in ${elapsed()}")
        val parser = kind.parser()
        raws.forEachIndexed { i, raw ->
            runCatching { parser.parse(raw) }
                .onSuccess { d -> println("  #${i + 1}  ${Instant.ofEpochSecond(d.startEpochSeconds)}  ${d.durationSeconds / 60}min  max=${(d.maxDepthMm ?: 0) / 1000.0}m  ${d.samples.size} samples") }
                .onFailure { println("  #${i + 1}  parse failed: ${it.message}") }
        }
    } catch (e: Exception) {
        println("Download failed after ${elapsed()}: ${e.message}")
    } finally {
        transport.close()
    }
}

private val KINDS = mapOf(
    "PREDATOR" to DiveComputerKind.SHEARWATER_PREDATOR,
    "PETREL1" to DiveComputerKind.SHEARWATER_PETREL,
)

private const val CLASSIC = "CLASSIC"

/**
 * The paired serial-port device addressed or named [target], else whose name contains it,
 * through rfcomm-bridge; with no target, one whose name says it is a [kind].
 */
private fun sppTransport(target: String?, kind: DiveComputerKind): Transport? {
    val bridge = MacRfcommTransport.locateBridge()
        ?: return null.also { println("No rfcomm-bridge found (macOS only; run through Gradle so it gets built).") }
    val paired = MacRfcommTransport.pairedDevices(bridge)
    println("Paired serial-port devices: ${if (paired.isEmpty()) "(none)" else paired.joinToString { "${it.name} ${it.address}" }}")
    val wanted = target ?: kind.displayName
    val device = when (target) {
        null -> paired.firstOrNull {
            if (kind.bleNames.isEmpty()) it.name.contains(kind.displayName.removePrefix("Shearwater "), ignoreCase = true) else kind.advertises(it.name)
        }
        else -> paired.firstOrNull { it.address.equals(target, ignoreCase = true) || it.name.equals(target, ignoreCase = true) }
            ?: paired.firstOrNull { it.name.contains(target, ignoreCase = true) }
    } ?: return null.also { println("No paired device matches \"$wanted\".") }
    println("Using ${device.name} (${device.address})")
    return MacRfcommTransport(bridge, device.address)
}

/**
 * The computer a Bluetooth LE scan finds with id [target] or a name containing it, through
 * ble-bridge; with no target, one whose name says it is a [kind] (as the app's picker
 * decides), or a nameless one.
 */
private fun bleTransport(target: String?, kind: DiveComputerKind): Transport? {
    val bridge = MacBleScanner.locateBridge()
        ?: return null.also { println("No ble-bridge found (macOS only; run through Gradle so it gets built).") }
    val scanner = MacBleScanner(bridge)
    val wanted = target ?: kind.displayName
    fun matches(d: ScannedDevice): Boolean = when {
        target != null -> d.id.equals(target, ignoreCase = true) || d.name?.contains(target, ignoreCase = true) == true
        else -> d.name?.let { kind.advertises(it) } ?: true
    }
    println("Scanning for $wanted; start Bluetooth on it")
    scanner.scan()
    do {
        val device = scanner.found().firstOrNull(::matches)
        if (device != null) {
            scanner.stop()
            println("Using ${device.name ?: "(no name)"} (${device.id})")
            return MacBleTransport(bridge, device.id)
        }
        Thread.sleep(250)
    } while (scanner.scanning)
    scanner.stop()
    println("Nothing found for $wanted${scanner.problem()?.let { ": $it" } ?: ""}")
    return null
}
