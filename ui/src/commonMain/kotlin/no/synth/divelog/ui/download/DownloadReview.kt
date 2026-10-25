package no.synth.divelog.ui.download

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import no.synth.divelog.core.logbook.download.DiveAction
import no.synth.divelog.core.logbook.download.DiveRef
import no.synth.divelog.core.logbook.download.DiveStatus
import no.synth.divelog.core.logbook.download.DownloadedDive
import no.synth.divelog.core.logbook.format.Format
import no.synth.divelog.core.model.units.UnitSystem

/**
 * Every dive a download read, against the logbook, before anything is imported. Rows
 * that need nothing from the user start checked (new dives, and overlaps merged into
 * the dive they overlap); rows that would change the logbook start unchecked (dives
 * already stored, which checking refreshes in place, and dives the user deleted). The
 * stored ones sit in a collapsed group. Full screen on a phone, a large dialog on a wider
 * window. Closing asks first, since the download would have to be run again.
 */
@Composable
fun DownloadReviewDialog(state: DownloadUiState.Reviewing, unitSystem: UnitSystem) {
    val dives = state.dives
    var choices by remember(state) { mutableStateOf(ReviewChoices(dives.map { it.status })) }
    var storedOpen by remember(state) { mutableStateOf(dives.all { it.status is DiveStatus.Stored }) }
    var confirmClose by remember(state) { mutableStateOf(false) }

    val stored = dives.indices.filter { dives[it].status is DiveStatus.Stored }
    val others = dives.indices.filter { dives[it].status !is DiveStatus.Stored }

    @Composable
    fun DiveRowAt(i: Int) = DiveRow(
        dive = dives[i],
        choices = choices,
        index = i,
        unitSystem = unitSystem,
        onChange = { choices = it },
    )

    Dialog(
        onDismissRequest = { confirmClose = true },
        properties = DialogProperties(dismissOnClickOutside = false, usePlatformDefaultWidth = false),
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val compact = maxWidth < 600.dp
            Surface(
                modifier = if (compact) {
                    Modifier.fillMaxSize()
                } else {
                    Modifier.align(Alignment.Center).widthIn(max = 720.dp).fillMaxWidth(0.9f).fillMaxHeight(0.85f)
                },
                shape = if (compact) RectangleShape else MaterialTheme.shapes.extraLarge,
            ) {
                Column(if (compact) Modifier.windowInsetsPadding(WindowInsets.safeDrawing) else Modifier) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                        IconButton(onClick = { confirmClose = true }) { Icon(Icons.Default.Close, contentDescription = "Close") }
                        Text("Review download", style = MaterialTheme.typography.titleLarge)
                    }
                    Text(
                        summaryLine(dives),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    ) {
                        TextButton(onClick = { choices = choices.suggested() }) { Text("Select suggested") }
                        TextButton(onClick = { choices = choices.all() }) { Text("Select all") }
                        TextButton(onClick = { choices = choices.none() }) { Text("Select none") }
                    }
                    HorizontalDivider()
                    LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                        items(others, key = { it }) { i -> DiveRowAt(i) }
                        if (stored.isNotEmpty()) {
                            item(key = "stored") {
                                StoredHeader(count = stored.size, open = storedOpen, onToggle = { storedOpen = !storedOpen })
                            }
                            if (storedOpen) items(stored, key = { it }) { i -> DiveRowAt(i) }
                        }
                    }
                    HorizontalDivider()
                    Row(
                        horizontalArrangement = Arrangement.End,
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                    ) {
                        Button(onClick = { state.onResolve(choices.actions) }) { Text(confirmLabel(choices.actions)) }
                    }
                }
            }
        }
    }

    if (confirmClose) {
        AlertDialog(
            onDismissRequest = { confirmClose = false },
            title = { Text("Leave the review?") },
            text = { Text("Nothing is imported. To get the ${Format.dives(dives.size)} back you would have to download again.") },
            confirmButton = {
                TextButton(
                    onClick = { state.onResolve(dives.map { DiveAction.SKIP }) },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Discard download") }
            },
            dismissButton = { TextButton(onClick = { confirmClose = false }) { Text("Keep reviewing") } },
        )
    }
}

/** The header that opens and closes the group of dives already in the logbook. */
@Composable
private fun StoredHeader(count: Int, open: Boolean, onToggle: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .clickable(role = Role.Button, onClick = onToggle)
            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" }
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "$count already in the logbook",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Icon(
                if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        }
        if (open) {
            Text(
                "Checking one refreshes it from the computer: its samples and computer data " +
                    "are replaced, its site, buddies and notes are kept.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One downloaded dive [index]: a checkbox, when and how deep, its status, and for a merge whether to keep it apart. */
@Composable
private fun DiveRow(
    dive: DownloadedDive,
    choices: ReviewChoices,
    index: Int,
    unitSystem: UnitSystem,
    onChange: (ReviewChoices) -> Unit,
) {
    val checked = choices.isChecked(index)
    val action = choices.action(index)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth()
            .toggleable(value = checked, enabled = choices.canCheck(index), role = Role.Checkbox) { onChange(choices.toggle(index)) }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Checkbox(checked = checked, onCheckedChange = null, enabled = choices.canCheck(index))
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
            Text(
                listOfNotNull(
                    dive.number?.let { "#$it" },
                    Format.dateTime(dive.startEpochSeconds, dive.utcOffsetSeconds),
                    Format.duration(dive.durationSeconds),
                    Format.depth(dive.maxDepthMm, unitSystem),
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                statusText(dive.status),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val into = mergeTarget(dive.status)
            if (into != null && checked && choices.canKeepApart(index)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = action == DiveAction.MERGE,
                        onClick = { onChange(choices.keepApart(index, false)) },
                        label = { Text("Merge into ${refLabel(into)}") },
                    )
                    FilterChip(
                        selected = action == DiveAction.IMPORT,
                        onClick = { onChange(choices.keepApart(index, true)) },
                        label = { Text("Keep separate") },
                    )
                }
            }
        }
    }
}

/** The dive that checking [status] merges into, if any. */
private fun mergeTarget(status: DiveStatus): DiveRef? = when (status) {
    is DiveStatus.Overlaps -> status.dive
    is DiveStatus.Deleted -> status.overlaps
    else -> null
}

private fun refLabel(ref: DiveRef): String =
    ref.number?.let { "#$it" } ?: Format.dateTime(ref.startEpochSeconds, ref.utcOffsetSeconds)

private fun statusText(status: DiveStatus): String = when (status) {
    DiveStatus.New -> "New"
    is DiveStatus.Deleted -> "You deleted this dive; check it to " +
        (status.overlaps?.let { "merge it into ${refLabel(it)} again" } ?: "import it again")
    is DiveStatus.Overlaps -> "Overlaps ${refLabel(status.dive)}" + (status.computer?.let { " ($it)" } ?: "")
    is DiveStatus.Stored -> when {
        status.joined -> "Joined into ${refLabel(status.dive)}; cannot be refreshed"
        status.secondComputer -> "Second computer on ${refLabel(status.dive)}"
        else -> "In the logbook as ${refLabel(status.dive)}"
    }
}

/** "Downloaded 100 dives: 4 new, 2 overlap a dive, 94 already in the logbook". */
private fun summaryLine(dives: List<DownloadedDive>): String {
    val parts = listOfNotNull(
        dives.count { it.status == DiveStatus.New }.takeIf { it > 0 }?.let { "$it new" },
        dives.count { it.status is DiveStatus.Overlaps }.takeIf { it > 0 }?.let { "$it overlap${if (it == 1) "s" else ""} a dive" },
        dives.count { it.status is DiveStatus.Deleted }.takeIf { it > 0 }?.let { "$it you deleted before" },
        dives.count { it.status is DiveStatus.Stored }.takeIf { it > 0 }?.let { "$it already in the logbook" },
    )
    return "Downloaded ${Format.dives(dives.size)}: ${parts.joinToString(", ")}"
}

/** Says what the import will do: "Import 4, merge 2, refresh 1". */
private fun confirmLabel(actions: List<DiveAction>): String {
    val imported = actions.count { it == DiveAction.IMPORT }
    val merged = actions.count { it == DiveAction.MERGE }
    val refreshed = actions.count { it == DiveAction.REFRESH }
    val parts = listOfNotNull(
        imported.takeIf { it > 0 }?.let { "import $it" },
        merged.takeIf { it > 0 }?.let { "merge $it" },
        refreshed.takeIf { it > 0 }?.let { "refresh $it" },
    )
    return if (parts.isEmpty()) "Import nothing" else parts.joinToString(", ").replaceFirstChar { it.uppercase() }
}
