// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.capture

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
import androidx.core.content.FileProvider
import androidx.lifecycle.viewmodel.compose.viewModel
import no.synth.divelog.core.transport.BluetoothDevices
import no.synth.divelog.core.transport.PairedDevice
import java.io.File

private fun hasConnectPermission(context: android.content.Context): Boolean =
    Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED

/** Debug screen: connect to a paired dive computer and capture the raw download. */
@Composable
fun CaptureScreen(viewModel: CaptureViewModel = viewModel()) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(hasConnectPermission(context)) }
    var devices by remember { mutableStateOf(if (granted) BluetoothDevices.paired(context) else emptyList()) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        granted = hasConnectPermission(context)
        if (granted) devices = BluetoothDevices.paired(context)
    }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Download (debug capture)", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Put the Predator in Bluetooth upload mode, then pick it below. " +
                "The raw exchange is saved so it can be shared off the phone.",
            style = MaterialTheme.typography.bodyMedium,
        )

        if (!granted) {
            Button(onClick = {
                permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
            }) { Text("Grant Bluetooth permission") }
            OutlinedButton(onClick = {
                context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
            }) { Text("Open Bluetooth settings to pair") }
            return@Column
        }

        when (val state = viewModel.state) {
            CaptureState.Idle -> DevicePicker(
                devices = devices,
                onRefresh = { devices = BluetoothDevices.paired(context) },
                onPick = { viewModel.start(it, File(context.filesDir, "captures")) },
            )

            is CaptureState.Connecting -> StatusCard("Connecting to ${state.deviceName}...") {
                CircularProgressIndicator()
                CancelButton(viewModel)
            }

            is CaptureState.Downloading -> StatusCard("Downloading...") {
                val fraction = if (state.totalBytes > 0) state.bytesRead.toFloat() / state.totalBytes else 0f
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text("${state.bytesRead} / ${state.totalBytes} bytes, ${state.dives} dives")
                CancelButton(viewModel)
            }

            is CaptureState.Done -> DoneCard(state.summary) { viewModel.reset() }

            is CaptureState.Failed -> StatusCard("Download failed") {
                Text(state.message, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(8.dp))
                Button(onClick = { viewModel.reset() }) { Text("Back") }
            }
        }
    }
}

@Composable
private fun DevicePicker(devices: List<PairedDevice>, onRefresh: () -> Unit, onPick: (PairedDevice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onRefresh) { Text("Refresh paired devices") }
        if (devices.isEmpty()) {
            Text("No paired devices. Pair the computer in system Bluetooth settings first.")
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(devices) { device ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp)) {
                            Text(device.name ?: "(unnamed)", style = MaterialTheme.typography.titleMedium)
                            Text(device.address, style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { onPick(device) }) { Text("Download & capture") }
                        }
                    }
                }
            }
        }
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

@Composable
private fun CancelButton(viewModel: CaptureViewModel) {
    OutlinedButton(onClick = { viewModel.cancel() }) { Text("Cancel") }
}

@Composable
private fun DoneCard(summary: CaptureSummary, onDismiss: () -> Unit) {
    val context = LocalContext.current
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Capture complete", style = MaterialTheme.typography.titleMedium)
            Text("Model: ${summary.model}")
            Text("Dives: ${summary.diveCount}")
            if (summary.fingerprints.isNotEmpty()) {
                Text(
                    "Fingerprints: ${summary.fingerprints.joinToString(", ")}",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = { shareCapture(context, summary) }) { Text("Share capture files") }
            OutlinedButton(onClick = onDismiss) { Text("Done") }
        }
    }
}

private fun shareCapture(context: android.content.Context, summary: CaptureSummary) {
    val authority = "${context.packageName}.fileprovider"
    val uris = ArrayList(
        listOf(summary.transcriptFile, summary.summaryFile).map {
            FileProvider.getUriForFile(context, authority, it)
        },
    )
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "text/plain"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share capture"))
}
