package no.synth.divelog.download

import android.Manifest
import android.content.Intent
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
            "Put the Predator in Bluetooth upload mode, then pick it below.",
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
                onPick = { viewModel.start(it, File(context.filesDir, "captures")) },
                onBack = onBack,
            )

            is DownloadState.Connecting -> StatusCard("Connecting to ${state.deviceName}...") {
                CircularProgressIndicator()
                OutlinedButton(onClick = { viewModel.cancel() }) { Text("Cancel") }
            }

            is DownloadState.Downloading -> StatusCard("Downloading...") {
                val fraction = if (state.totalBytes > 0) state.bytesRead.toFloat() / state.totalBytes else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("${state.bytesRead} / ${state.totalBytes} bytes")
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
            }

            is DownloadState.Failed -> StatusCard("Download failed") {
                Text(state.message, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick = onBack) { Text("Back") }
            }
        }
    }
}

@Composable
private fun DevicePicker(
    devices: List<PairedDevice>,
    onRefresh: () -> Unit,
    onPick: (PairedDevice) -> Unit,
    onBack: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRefresh) { Text("Refresh paired devices") }
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
                            Button(onClick = { onPick(device) }) { Text("Download") }
                        }
                    }
                }
            }
        }
        OutlinedButton(onClick = onBack) { Text("Back") }
    }
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
