package no.synth.divelog.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.logbook.io.LogbookIo

/**
 * Exports format [formatId] of the logbook, or only [diveIds] when given, and runs [onDone]
 * once the file is saved or shared (not when the user cancels).
 */
typealias ExportAction = (formatId: String, diveIds: Set<Long>?, onDone: () -> Unit) -> Unit

/** What an export can do on this platform: [save] and [share], a null one hidden; [lastFormatId] is preselected. */
class ExportActions(val save: ExportAction?, val share: ExportAction?, val lastFormatId: () -> String?) {
    val available: Boolean get() = save != null || share != null
}

/**
 * Picks an export format, then saves or shares it through [actions]. [title] names what is
 * exported; only [diveIds] are exported when given. [onExported] runs once the file is out.
 */
@Composable
fun ExportDialog(
    title: String,
    actions: ExportActions,
    diveIds: Set<Long>?,
    onExported: () -> Unit,
    onDismiss: () -> Unit,
) {
    val targets = remember { LogbookIo.exportTargets() }
    var formatId by remember {
        mutableStateOf(actions.lastFormatId()?.takeIf { id -> targets.any { it.id == id } } ?: targets.first().id)
    }

    @Composable
    fun ActionButton(label: String, action: ExportAction) {
        TextButton(onClick = {
            onDismiss()
            action(formatId, diveIds, onExported)
        }) { Text(label) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.selectableGroup()) {
                targets.forEach { target ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(
                            selected = formatId == target.id,
                            role = Role.RadioButton,
                            onClick = { formatId = target.id },
                        ),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = formatId == target.id, onClick = null)
                        Spacer(Modifier.width(12.dp))
                        Text(target.displayName, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = {
            Row {
                actions.share?.let { ActionButton("Share", it) }
                actions.save?.let { ActionButton("Save", it) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
