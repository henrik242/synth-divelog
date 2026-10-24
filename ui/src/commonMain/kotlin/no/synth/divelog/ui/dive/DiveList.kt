package no.synth.divelog.ui.dive

import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.logbook.format.Format
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.units.UnitSystem

/**
 * One dive summary row: site (or date) as the headline, depth emphasized. A non-null
 * [selected] means the list is picking dives: a checkbox leads and a tap ([onClick]) toggles
 * it. [onPick] makes the row pickable: a long press, or a click with Ctrl or Cmd held, picks
 * it; a click with Shift held picks the range up to it (range = true).
 */
@Composable
fun DiveRow(
    dive: Dive,
    unitSystem: UnitSystem,
    onClick: () -> Unit,
    siteName: String? = null,
    source: String? = null,
    selected: Boolean? = null,
    onPick: ((range: Boolean) -> Unit)? = null,
) {
    val hasSite = !siteName.isNullOrBlank()
    val headline = siteName?.takeIf { it.isNotBlank() }
        ?: Format.date(dive.startEpochSeconds, dive.utcOffsetSeconds)
    val secondary = buildList {
        dive.number?.let { add("#$it") }
        source?.let { add(it) }
        add(
            if (hasSite) Format.dateTime(dive.startEpochSeconds, dive.utcOffsetSeconds)
            else Format.time(dive.startEpochSeconds, dive.utcOffsetSeconds),
        )
    }.joinToString(" · ")

    val held = remember { HeldKeys() }
    val click = {
        when {
            onPick != null && held.shift -> onPick(true)
            onPick != null && held.ctrlOrCmd -> onPick(false)
            else -> onClick()
        }
    }
    val interaction = if (selected != null) {
        Modifier.toggleable(value = selected, role = Role.Checkbox, onValueChange = { click() })
    } else {
        Modifier.combinedClickable(
            onClick = click,
            onLongClick = onPick?.let { pick -> { pick(false) } },
            onLongClickLabel = onPick?.let { "Select" },
        )
    }

    Row(
        Modifier.fillMaxWidth()
            .then(if (onPick != null) Modifier.trackHeldKeys(held) else Modifier)
            .then(interaction)
            .background(if (selected == true) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selected != null) {
            Checkbox(checked = selected, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(headline, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                secondary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 12.dp)) {
            Text(
                Format.depth(dive.maxDepthMm, unitSystem),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "${Format.duration(dive.durationSeconds)} min",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** The modifier keys held during the latest pointer event over a row. */
private class HeldKeys {
    var shift = false
    var ctrlOrCmd = false
}

/** Keeps [held] current; the initial pass sees each event before the row's click handling does. */
private fun Modifier.trackHeldKeys(held: HeldKeys): Modifier = pointerInput(held) {
    awaitPointerEventScope {
        while (true) {
            val keys = awaitPointerEvent(PointerEventPass.Initial).keyboardModifiers
            held.shift = keys.isShiftPressed
            held.ctrlOrCmd = keys.isCtrlPressed || keys.isMetaPressed
        }
    }
}

/** A plain list of [dives]; tapping one hands its id to [onOpenDive]. */
@Composable
fun DiveList(container: AppContainer, dives: List<Dive>, unitSystem: UnitSystem, onOpenDive: (Long) -> Unit) {
    val sources = remember(dives) { diveSourceLabels(container) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(dives) { dive ->
            DiveRow(dive, unitSystem, onClick = { onOpenDive(dive.id) }, source = sources[dive.id])
            HorizontalDivider()
        }
    }
}

/** Compact source label per dive id for list rows, from a single query. */
fun diveSourceLabels(container: AppContainer): Map<Long, String> = sourceLabels(container.dives.sourcesByDive())

fun sourceLabels(sourcesByDive: Map<Long, List<Device?>>): Map<Long, String> =
    sourcesByDive.mapValues { (_, sources) -> sources.map { deviceName(it) }.distinct().joinToString(" + ") }
