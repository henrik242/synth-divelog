package no.synth.divelog.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.io.LogbookIo

@Composable
fun SettingsSection(
    container: AppContainer,
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
    onExport: (formatId: String) -> Unit = {},
    onReparse: () -> Unit = {},
    cloudEnabled: Boolean = false,
    initialCloudEmail: String = "",
    initialCloudPassword: String = "",
    onCloudConfigChange: (email: String, pass: String) -> Unit = { _, _ -> },
    onCloudPull: (email: String, pass: String) -> Unit = { _, _ -> },
    onCloudPush: (email: String, pass: String) -> Unit = { _, _ -> },
    onOpenComputers: () -> Unit = {},
) {
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

        var cloudDialog by remember { mutableStateOf<CloudAction?>(null) }

        // File import lives with the Add-dives button; only cloud import stays here.
        if (cloudEnabled) {
            Text("Import", style = MaterialTheme.typography.titleMedium)
            OutlinedButton(onClick = { cloudDialog = CloudAction.IMPORT }, modifier = Modifier.fillMaxWidth()) {
                Text("Import from Subsurface cloud")
            }

            HorizontalDivider()
        }

        Text("Export", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // One button per registered file format, so a new format appears here too.
            LogbookIo.formats().forEach { format ->
                OutlinedButton(onClick = { onExport(format.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Export ${format.displayName}")
                }
            }
            if (cloudEnabled) {
                OutlinedButton(onClick = { cloudDialog = CloudAction.EXPORT }, modifier = Modifier.fillMaxWidth()) {
                    Text("Export to Subsurface cloud")
                }
            }
        }

        cloudDialog?.let { action ->
            CloudDialog(
                action = action,
                initialEmail = initialCloudEmail,
                initialPassword = initialCloudPassword,
                onConfirm = { email, pass ->
                    onCloudConfigChange(email, pass)
                    if (action == CloudAction.IMPORT) onCloudPull(email, pass) else onCloudPush(email, pass)
                    cloudDialog = null
                },
                onDismiss = { cloudDialog = null },
            )
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
        OutlinedButton(onClick = onOpenComputers, modifier = Modifier.fillMaxWidth()) {
            Text("Dive computers")
        }
    }
}

private enum class CloudAction(val title: String, val confirm: String) {
    IMPORT("Import from Subsurface cloud", "Import"),
    EXPORT("Export to Subsurface cloud", "Export"),
}

@Composable
private fun CloudDialog(
    action: CloudAction,
    initialEmail: String,
    initialPassword: String,
    onConfirm: (email: String, pass: String) -> Unit,
    onDismiss: () -> Unit,
) {
    var email by remember { mutableStateOf(initialEmail) }
    var pass by remember { mutableStateOf(initialPassword) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(action.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The Subsurface cloud stores your logbook as a git repository. Sign in " +
                        "with the email and password of your Subsurface cloud account.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    email, { email = it },
                    label = { Text("Subsurface cloud email") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    pass, { pass = it },
                    label = { Text("Subsurface cloud password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(email.trim(), pass) },
                enabled = email.isNotBlank() && pass.isNotBlank(),
            ) {
                Text(action.confirm)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
