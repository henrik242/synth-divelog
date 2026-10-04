package no.synth.divelog.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.db.ImportResult
import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveLogParser
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.suunto.SuuntoD9Dump
import no.synth.divelog.core.divecomputer.suunto.SuuntoD9Parser
import no.synth.divelog.core.divecomputer.suunto.SuuntoFamily
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.transport.JSerialCommTransport
import no.synth.divelog.ui.AppContainer

/** Progress of the desktop wired download, surfaced to the Compose UI. */
sealed interface DesktopDownloadState {
    data object Idle : DesktopDownloadState
    data class Running(val fraction: Float, val label: String) : DesktopDownloadState
}

/**
 * Serial Suunto families the desktop wired download offers. Shearwater/Petrel is
 * Bluetooth, handled by the Android download, so it is not here.
 */
val desktopDownloadFamilies: List<SuuntoFamily> = listOf(SuuntoFamily.D9, SuuntoFamily.VYPER)

/**
 * Preselect a serial port that looks like a USB-serial adapter, else the first port.
 * Returns null when there are no ports.
 */
fun preferredPortName(ports: List<String>): String? =
    ports.firstOrNull {
        it.contains("usbserial", true) || it.contains("tty.usb", true) || it.contains("ttyUSB", true)
    } ?: ports.firstOrNull()

/**
 * Run a wired download for the chosen [family] on [portName] end to end, parse the
 * dives and import them through the shared pipeline so dedupe, device identity and the
 * data refresh match file import and the Android download. Returns a user-facing
 * summary. Blocking; call off the UI thread.
 */
fun downloadToLogbook(
    container: AppContainer,
    family: SuuntoFamily,
    portName: String,
    isCancelled: () -> Boolean = { false },
    onProgress: (fraction: Float, label: String) -> Unit = { _, _ -> },
): String = when (family) {
    SuuntoFamily.D9 -> downloadD9(container, portName, isCancelled, onProgress)
    SuuntoFamily.VYPER -> downloadVyper(container, portName, isCancelled, onProgress)
}

/**
 * HelO2/D9 download over the proven raw read path: capture the memory, walk and parse
 * the ring, import through the shared pipeline.
 */
private fun downloadD9(
    container: AppContainer,
    portName: String,
    isCancelled: () -> Boolean,
    onProgress: (fraction: Float, label: String) -> Unit,
): String {
    onProgress(0f, "Reading from the dive computer")
    val image = captureD9Image(portName, isCancelled) { done, total ->
        onProgress(done.toFloat() / total, "Reading memory ${done * 100 / total}%")
    }

    onProgress(1f, "Parsing dives")
    val raws = extractD9Dives(image)
    val deviceId = container.devices.getOrCreate(d9Device(image))
    return importRaws(container, raws, SuuntoD9Parser(), deviceId)
}

/**
 * Zoop/Vyper download over the standard protocol path: open the half-duplex serial
 * line with the family's line settings, download the raw dives, parse and import them
 * through the same shared pipeline.
 */
private fun downloadVyper(
    container: AppContainer,
    portName: String,
    isCancelled: () -> Boolean,
    onProgress: (fraction: Float, label: String) -> Unit,
): String {
    onProgress(0f, "Connecting to the dive computer")
    val transport = JSerialCommTransport.byName(portName, SuuntoFamily.VYPER.serialParams)
    transport.open()
    try {
        val protocol = SuuntoFamily.VYPER.protocol(transport)
        val info = runCatching { protocol.readDeviceInfo() }.getOrNull()
        val listener = object : DownloadListener {
            override fun onProgress(current: Int, total: Int) {
                if (total > 0) onProgress(current.toFloat() / total, "Reading memory ${current * 100 / total}%")
            }
        }
        val raws = protocol.download(
            knownFingerprint = null,
            listener = listener,
            cancel = CancellationSignal { isCancelled() },
            limit = null,
        )
        onProgress(1f, "Parsing dives")
        val parser = SuuntoFamily.VYPER.parser() ?: return "No parser for the Vyper family"
        val deviceId = container.devices.getOrCreate(vyperDevice(info))
        return importRaws(container, raws, parser, deviceId)
    } finally {
        runCatching { transport.close() }
    }
}

/** Parse and import raw dives for a device, counting new vs already-stored. */
private fun importRaws(
    container: AppContainer,
    raws: List<RawDive>,
    parser: DiveLogParser,
    deviceId: Long,
): String {
    var imported = 0
    var skipped = 0
    for (raw in raws) {
        val incoming = runCatching { parser.parse(raw) }.getOrNull()?.copy(deviceId = deviceId) ?: continue
        when (container.dives.import(incoming) { false }) {
            is ImportResult.SkippedDuplicate -> skipped++
            else -> imported++
        }
    }
    return when {
        imported > 0 ->
            "Imported $imported new dive${plural(imported)}" +
                if (skipped > 0) ", skipped $skipped already in the logbook" else ""
        skipped > 0 -> "No new dives ($skipped already in the logbook)"
        raws.isEmpty() -> "No dives found on the device"
        else -> "No new dives"
    }
}

private fun plural(n: Int) = if (n == 1) "" else "s"

/**
 * Stable identity for the connected D9/HelO2 so repeated downloads dedupe against the
 * same device row (dive dedupe keys on device plus fingerprint). Uses the serial read
 * from the image when it is present, falling back to a fixed tag for the single
 * USB-serial device. The synthetic address is just the identity key getOrCreate dedupes
 * on; there is no Bluetooth here.
 */
private fun d9Device(image: ByteArray): Device {
    val serial = image.copyOfRange(SuuntoD9Dump.SERIAL_OFFSET, SuuntoD9Dump.SERIAL_OFFSET + SuuntoD9Dump.SERIAL_SIZE)
    val readable = serial.any { it != 0xFF.toByte() && it != 0x00.toByte() }
    val serialHex = if (readable) serial.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') } else null
    return Device(
        vendor = "Suunto",
        model = "HelO2 / D9",
        serial = serialHex,
        bluetoothAddress = "usb-serial:suunto-d9" + (serialHex?.let { ":$it" } ?: ""),
    )
}

/**
 * Stable identity for the connected Vyper-family computer. The protocol does not read a
 * serial, so the model from device info plus a fixed per-family tag is the dedupe key.
 */
private fun vyperDevice(info: DeviceInfo?): Device = Device(
    vendor = info?.vendor ?: "Suunto",
    model = info?.model ?: "Vyper",
    bluetoothAddress = "usb-serial:suunto-vyper",
)

/**
 * Selection step shown before a wired download: pick the dive computer family and the
 * serial port, then start the capture. Only serial Suunto families are offered.
 */
@Composable
fun DesktopDownloadPickerDialog(
    onStart: (family: SuuntoFamily, portName: String) -> Unit,
    onCancel: () -> Unit,
) {
    var family by remember { mutableStateOf(SuuntoFamily.D9) }
    var ports by remember { mutableStateOf(JSerialCommTransport.availablePortNames()) }
    var port by remember { mutableStateOf(preferredPortName(ports)) }
    var portMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text("Download dives") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("Dive computer", style = MaterialTheme.typography.labelLarge)
                desktopDownloadFamilies.forEach { f ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { family = f },
                    ) {
                        RadioButton(selected = family == f, onClick = { family = f })
                        Text("Suunto ${f.displayName}")
                    }
                }

                Text("Serial port", style = MaterialTheme.typography.labelLarge)
                if (ports.isEmpty()) {
                    Text("No serial ports found. Connect the cable, then refresh.")
                } else {
                    Box {
                        OutlinedButton(onClick = { portMenuOpen = true }) {
                            Text(port ?: "Choose a port")
                        }
                        DropdownMenu(expanded = portMenuOpen, onDismissRequest = { portMenuOpen = false }) {
                            ports.forEach { p ->
                                DropdownMenuItem(
                                    text = { Text(p) },
                                    onClick = { port = p; portMenuOpen = false },
                                )
                            }
                        }
                    }
                }
                TextButton(onClick = {
                    ports = JSerialCommTransport.availablePortNames()
                    if (port == null || port !in ports) port = preferredPortName(ports)
                }) { Text("Refresh ports") }
            }
        },
        confirmButton = {
            TextButton(enabled = port != null, onClick = { port?.let { onStart(family, it) } }) {
                Text("Download")
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}

/** Modal progress while a wired download runs, with a cancel action. */
@Composable
fun DesktopDownloadDialog(state: DesktopDownloadState.Running, onCancel: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Downloading dives") },
        text = {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(state.label)
                if (state.fraction > 0f) {
                    LinearProgressIndicator(progress = { state.fraction }, modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onCancel) { Text("Cancel") } },
    )
}
