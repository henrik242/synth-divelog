package no.synth.divelog.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.logbook.io.LogbookIo
import no.synth.divelog.core.logbook.settings.AppSettings
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.BuildInfo
import no.synth.divelog.ui.components.CloudCredentialsDialog

/**
 * Units, export, maintenance, dive computers, privacy and attributions. A null [onCloudPush] hides
 * the cloud export; its credentials are kept in [settings]. A null [onCrashReportingChange] hides
 * the crash-report switch.
 */
@Composable
fun SettingsSection(
    settings: AppSettings,
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
    onExport: (formatId: String) -> Unit,
    onReparse: () -> Unit,
    onCloudPush: ((email: String, pass: String) -> Unit)?,
    onOpenComputers: () -> Unit,
    onOpenAttributions: () -> Unit,
    onCrashReportingChange: ((Boolean) -> Unit)?,
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

        var cloudExportOpen by remember { mutableStateOf(false) }

        // Cloud import lives with the Add-dives button; only cloud export stays here.
        Text("Export", style = MaterialTheme.typography.titleMedium)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // One button per export, so a new format appears here too.
            LogbookIo.exportTargets().forEach { target ->
                OutlinedButton(onClick = { onExport(target.id) }, modifier = Modifier.fillMaxWidth()) {
                    Text("Export ${target.displayName}")
                }
            }
            if (onCloudPush != null) {
                OutlinedButton(onClick = { cloudExportOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Export to Subsurface cloud")
                }
            }
        }

        if (cloudExportOpen && onCloudPush != null) {
            CloudCredentialsDialog(
                title = "Export to Subsurface cloud",
                confirmLabel = "Export",
                initialEmail = settings.cloudEmail,
                initialPassword = settings.cloudPassword,
                onConfirm = { email, pass ->
                    settings.cloudEmail = email
                    settings.cloudPassword = pass
                    onCloudPush(email, pass)
                    cloudExportOpen = false
                },
                onDismiss = { cloudExportOpen = false },
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

        HorizontalDivider()

        if (onCrashReportingChange != null) {
            var crashReporting by remember { mutableStateOf(settings.crashReporting) }
            Text("Privacy", style = MaterialTheme.typography.titleMedium)
            Row(
                Modifier.fillMaxWidth().clickable {
                    crashReporting = !crashReporting
                    onCrashReportingChange(crashReporting)
                },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Send crash reports", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Sends a report to the developer when the app crashes. It contains no dive data.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = crashReporting,
                    onCheckedChange = {
                        crashReporting = it
                        onCrashReportingChange(it)
                    },
                )
            }

            HorizontalDivider()
        }

        Text("About", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(onClick = onOpenAttributions, modifier = Modifier.fillMaxWidth()) {
            Text("Attributions")
        }

        Text(
            BuildInfo.VERSION_INFO,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.CenterHorizontally).padding(16.dp),
        )
    }
}
