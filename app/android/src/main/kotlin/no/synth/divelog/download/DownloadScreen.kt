package no.synth.divelog.download

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import no.synth.divelog.core.transport.BluetoothDevices
import no.synth.divelog.core.transport.PairedDevice
import no.synth.divelog.ui.AppContainer
import java.io.File

private fun hasConnectPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

@Composable
fun DownloadScreen(
    container: AppContainer,
    onImported: () -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: DownloadViewModel = viewModel { DownloadViewModel(container) }
    var granted by remember { mutableStateOf(hasConnectPermission(context)) }
    var devices by remember { mutableStateOf(if (granted) BluetoothDevices.paired(context) else emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = hasConnectPermission(context)
        if (granted) devices = BluetoothDevices.paired(context)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Download dives", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Put the dive computer in Bluetooth upload mode, then pick it below. " +
                "If it asks for a pairing code, it is 0000.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!granted) {
            Button(onClick = { permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT)) }) {
                Text("Grant Bluetooth permission")
            }
            OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                Text("Open Bluetooth settings to pair")
            }
            OutlinedButton(onClick = onBack) { Text("Back") }
            return@Column
        }

        when (val state = viewModel.state) {
            DownloadState.Idle -> DevicePicker(
                devices = devices,
                onRefresh = { devices = BluetoothDevices.paired(context) },
                onPick = { device, limit -> viewModel.start(device, File(context.filesDir, "captures"), limit) },
                onBack = onBack,
            )

            is DownloadState.Connecting -> StatusCard("Connecting to ${state.deviceName}...") {
                CircularProgressIndicator()
                OutlinedButton(onClick = { viewModel.cancel() }) { Text("Cancel") }
            }

            is DownloadState.Downloading -> StatusCard("Downloading...") {
                if (state.totalBytes > 0) {
                    // Known total (Predator full dump): show a real progress bar.
                    LinearProgressIndicator(
                        progress = { state.bytesRead.toFloat() / state.totalBytes },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text("${state.bytesRead} / ${state.totalBytes} bytes")
                } else {
                    // Unknown total (Petrel per-dive compressed read): count dives instead.
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    if (state.diveCount > 0) {
                        Text("Dive ${state.diveIndex} of ${state.diveCount} (${state.bytesRead} bytes)")
                    } else {
                        Text("${state.bytesRead} bytes")
                    }
                }
                state.etaSeconds?.let { Text("About ${formatEta(it)} left") }
                OutlinedButton(onClick = { viewModel.cancel() }) { Text("Cancel") }
            }

            DownloadState.Importing -> StatusCard("Importing...") { CircularProgressIndicator() }

            is DownloadState.Reviewing -> {
                val item = state.items[state.index]
                StatusCard("Merge dive? (${state.index + 1}/${state.items.size})") {
                    Text("This download overlaps an existing dive - likely the same dive from another computer.")
                    Spacer(Modifier.height(8.dp))
                    Text("Incoming:  ${item.incomingLabel}", style = MaterialTheme.typography.bodyMedium)
                    Text("Existing:  ${item.existingLabel}", style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { viewModel.resolveReview(merge = true) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Merge into existing dive")
                    }
                    OutlinedButton(onClick = { viewModel.resolveReview(merge = false) }, modifier = Modifier.fillMaxWidth()) {
                        Text("Keep as separate dive")
                    }
                }
            }

            is DownloadState.Done -> StatusCard("Download complete") {
                Text(
                    "Imported ${state.imported} new, merged ${state.merged}, " +
                        "skipped ${state.skipped} already stored.",
                )
                Button(onClick = onImported) { Text("Done") }
                OutlinedButton(onClick = { viewModel.reset() }) { Text("Download another") }
            }

            is DownloadState.Failed -> StatusCard("Download failed") {
                Text(state.message, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.reset() }) { Text("Try again") }
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
    }
}

private data class DownloadAmount(val label: String, val limit: Int?)

private val DOWNLOAD_AMOUNTS = listOf(
    DownloadAmount("Latest 5", 5),
    DownloadAmount("Latest 25", 25),
    DownloadAmount("Latest 100", 100),
    DownloadAmount("All", null),
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DevicePicker(
    devices: List<PairedDevice>,
    onRefresh: () -> Unit,
    onPick: (PairedDevice, Int?) -> Unit,
    onBack: () -> Unit,
) {
    var amount by remember { mutableStateOf(DOWNLOAD_AMOUNTS.last()) } // default: All
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRefresh) { Text("Refresh paired devices") }

        Text("How many dives", style = MaterialTheme.typography.labelLarge)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DOWNLOAD_AMOUNTS.forEach { option ->
                FilterChip(
                    selected = amount == option,
                    onClick = { amount = option },
                    label = { Text(option.label) },
                )
            }
        }

        if (devices.isEmpty()) {
            Text("No paired devices. Pair the computer in system Bluetooth settings first.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices) { device ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(device.name ?: "(unnamed)", style = MaterialTheme.typography.titleMedium)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { onPick(device, amount.limit) }) { Text("Download ${amount.label.lowercase()}") }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
}

private fun formatEta(seconds: Long): String = when {
    seconds < 60 -> "${seconds}s"
    seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
    else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
}

@Composable
private fun StatusCard(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}
