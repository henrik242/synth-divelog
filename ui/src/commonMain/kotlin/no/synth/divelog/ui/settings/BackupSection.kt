package no.synth.divelog.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.logbook.format.Format
import no.synth.divelog.core.logbook.io.AutoBackup
import no.synth.divelog.ui.BackupFlow

/** Saving, sharing and restoring a backup, and the automatic backup to a folder. */
@Composable
internal fun BackupSection(backup: BackupFlow) {
    Text("Backup", style = MaterialTheme.typography.titleMedium)
    Text(
        "The whole logbook in one file, including the raw dive-computer data. Settings and the cloud login are not included. " +
            (backup.lastBackupEpochSeconds?.let { "Last backup: ${Format.localDateTime(it)}." } ?: "No backup yet."),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        backup.save?.let { OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Save backup") } }
        backup.share?.let { OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Share backup") } }
        backup.restore?.let { OutlinedButton(onClick = it, modifier = Modifier.fillMaxWidth()) { Text("Restore backup") } }
    }

    val choose = backup.chooseFolder ?: return
    Text("Automatic backup", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
    val folder = backup.folder
    val failure = backup.automaticFailure
    Text(
        if (folder == null) {
            "Writes a backup to a folder a minute after the logbook changes. A folder that a sync app copies to the " +
                "cloud takes the backups off this device."
        } else {
            "On, to ${backup.folderLabel ?: "the chosen folder"}. " +
                (backup.lastAutomaticEpochSeconds?.let { "Last written ${Format.localDateTime(it)}. " } ?: "") +
                "One backup a day is kept for ${AutoBackup.KEEP_DAYS} days, one a week for ${AutoBackup.KEEP_WEEKS} weeks " +
                "and one a month for ${AutoBackup.KEEP_MONTHS} months."
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (folder != null && failure != null) {
        val (error, at) = failure
        Text(
            "The last try failed on ${Format.localDateTime(at)}: ${error.trimEnd('.')}.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
    if (folder == null) {
        OutlinedButton(onClick = choose, modifier = Modifier.fillMaxWidth()) { Text("Choose folder") }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = choose, modifier = Modifier.weight(1f)) { Text("Change folder") }
            OutlinedButton(onClick = backup::stopAutomatic, modifier = Modifier.weight(1f)) { Text("Turn off") }
        }
    }
}
