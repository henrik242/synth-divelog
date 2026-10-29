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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import no.synth.divelog.core.logbook.settings.AppSettings
import no.synth.divelog.core.model.ThemeMode
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.BackupFlow
import no.synth.divelog.ui.BuildInfo
import no.synth.divelog.ui.components.CloudCredentialsDialog
import no.synth.divelog.ui.components.ExportActions
import no.synth.divelog.ui.components.ExportDialog

/**
 * Units, theme, export, backup, maintenance, dive computers, privacy and attributions. A null [onCloudPush] hides
 * the cloud export; its credentials are kept in [settings]. A null [onCrashReportingChange] hides
 * the crash-report switch. [exportActions] says how an export can leave the app; with none, export is hidden.
 * The backup section shows what [backup] can do here, and is hidden when it can do nothing.
 */
@Composable
internal fun SettingsSection(
    settings: AppSettings,
    unitSystem: UnitSystem,
    onUnitSystemChange: (UnitSystem) -> Unit,
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    exportActions: ExportActions,
    backup: BackupFlow,
    onReparse: () -> Unit,
    onForgetAllDives: () -> Unit,
    onCloudPush: ((email: String, pass: String) -> Unit)?,
    onOpenAttributions: () -> Unit,
    onCrashReportingChange: ((Boolean) -> Unit)?,
) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        var unitsOpen by remember { mutableStateOf(false) }
        var themeOpen by remember { mutableStateOf(false) }

        SettingValueRow("Units", unitLabel(unitSystem)) { unitsOpen = true }

        HorizontalDivider()

        SettingValueRow("Theme", themeLabel(themeMode)) { themeOpen = true }

        HorizontalDivider()

        if (unitsOpen) {
            ChoiceDialog(
                title = "Units",
                options = UnitSystem.entries,
                selected = unitSystem,
                label = ::unitLabel,
                onSelect = onUnitSystemChange,
                onDismiss = { unitsOpen = false },
            )
        }
        if (themeOpen) {
            ChoiceDialog(
                title = "Theme",
                options = ThemeMode.entries,
                selected = themeMode,
                label = ::themeLabel,
                onSelect = onThemeModeChange,
                onDismiss = { themeOpen = false },
            )
        }

        var cloudExportOpen by remember { mutableStateOf(false) }
        var exportOpen by remember { mutableStateOf(false) }

        // Cloud import lives with the Add-dives button; only cloud export stays here.
        if (exportActions.available || onCloudPush != null) {
            Text("Export", style = MaterialTheme.typography.titleMedium)
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (exportActions.available) {
                OutlinedButton(onClick = { exportOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Export logbook")
                }
                Text(
                    "To export only some dives, long-press one in the Dives list, or tap the checklist button above it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (onCloudPush != null) {
                OutlinedButton(onClick = { cloudExportOpen = true }, modifier = Modifier.fillMaxWidth()) {
                    Text("Export to Subsurface cloud")
                }
            }
        }

        if (exportOpen) {
            ExportDialog(
                title = "Export logbook",
                actions = exportActions,
                diveIds = null,
                onExported = {},
                onDismiss = { exportOpen = false },
            )
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

        if (backup.available) {
            BackupSection(backup)

            HorizontalDivider()
        }

        Text("Maintenance", style = MaterialTheme.typography.titleMedium)
        Text(
            "Re-reads every stored dive from its saved raw download and rebuilds the " +
                "profile. Use after an app update that improves dive decoding. Your notes, " +
                "ratings, sites and dive numbers are kept.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(onClick = onReparse) { Text("Re-parse all dives") }

        var forgetOpen by remember { mutableStateOf(false) }
        Text(
            "Deletes every dive in the logbook with its profiles, tanks and notes. Dive computers, " +
                "sites, buddies and tags are kept, and the next download fetches all dives again.",
            style = MaterialTheme.typography.bodyMedium,
        )
        OutlinedButton(
            onClick = { forgetOpen = true },
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
        ) { Text("Forget all dives") }
        if (forgetOpen) {
            ForgetAllDivesDialog(
                onForget = {
                    forgetOpen = false
                    onForgetAllDives()
                },
                onDismiss = { forgetOpen = false },
            )
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

/** Asks before forgetting every dive; this cannot be undone. */
@Composable
private fun ForgetAllDivesDialog(onForget: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Forget all dives?") },
        text = {
            Text(
                "Are you really sure? This deletes every dive in the logbook and cannot be undone. " +
                    "Export the logbook first if you may want it back.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onForget,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Forget all dives") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A whole setting on one line: its name to the left, its current value to the right, tapped to change it. */
@Composable
private fun SettingValueRow(name: String, value: String, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Lists [options] with [selected] marked; choosing one applies [onSelect] and closes. */
@Composable
private fun <T> ChoiceDialog(
    title: String,
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelect: (T) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                options.forEach { option ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable {
                                onSelect(option)
                                onDismiss()
                            }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = option == selected, onClick = null)
                        Text(
                            label(option),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(start = 12.dp),
                        )
                    }
                }
            }
        },
        confirmButton = {},
    )
}

/** The value shown for the units setting. */
private fun unitLabel(system: UnitSystem): String = if (system == UnitSystem.METRIC) "Metric" else "Imperial"

/** The value shown for the theme setting. */
private fun themeLabel(mode: ThemeMode): String = when (mode) {
    ThemeMode.SYSTEM -> "System"
    ThemeMode.LIGHT -> "Light"
    ThemeMode.DARK -> "Dark"
}
