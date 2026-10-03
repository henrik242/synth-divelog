package no.synth.divelog.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer

@Composable
fun SettingsSection(
    container: AppContainer,
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
    onImport: () -> Unit = {},
    onExport: (formatId: String) -> Unit = {},
    onReparse: () -> Unit = {},
) {
    var version by remember { mutableIntStateOf(0) }
    val devices = remember(version) { container.devices.all() }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Units", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            UnitSystem.entries.forEach { system ->
                FilterChip(
                    selected = unitSystem == system,
                    onClick = { onUnitSystemChange(system) },
                    label = { Text(if (system == UnitSystem.METRIC) "Metric" else "Imperial") },
                )
            }
        }

        HorizontalDivider()

        Text("Import / export", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onImport) { Text("Import file") }
            OutlinedButton(onClick = { onExport("subsurface-xml") }) { Text("Export XML") }
            OutlinedButton(onClick = { onExport("uddf") }) { Text("Export UDDF") }
        }

        HorizontalDivider()

        Text("Maintenance", style = MaterialTheme.typography.titleMedium)
        Text(
            "Re-reads every stored dive from its saved raw download and rebuilds the " +
                "profile. Use after an app update that improves dive decoding. Your notes, " +
                "ratings, sites and dive numbers are kept.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onReparse) { Text("Re-parse all dives") }

        HorizontalDivider()

        Text("Dive computers", style = MaterialTheme.typography.titleMedium)
        if (devices.isEmpty()) {
            Text("None yet. They are added when you download.", style = MaterialTheme.typography.bodyMedium)
        } else {
            devices.forEach { device ->
                key(device.id) {
                    DeviceRow(
                        device = device,
                        onRename = { container.devices.update(device.copy(nickname = it.ifBlank { null })); version++ },
                        onForget = { container.devices.delete(device.id); version++ },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: Device, onRename: (String) -> Unit, onForget: () -> Unit) {
    var nickname by remember { mutableStateOf(device.nickname ?: "") }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("${device.vendor} ${device.model}", style = MaterialTheme.typography.titleSmall)
        device.bluetoothAddress?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = nickname,
                onValueChange = { nickname = it },
                label = { Text("Nickname") },
                modifier = Modifier.weight(1f),
                singleLine = true,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onRename(nickname) }) { Text("Save") }
            OutlinedButton(onClick = onForget) { Text("Forget") }
        }
        HorizontalDivider(Modifier.padding(top = 8.dp))
    }
}
