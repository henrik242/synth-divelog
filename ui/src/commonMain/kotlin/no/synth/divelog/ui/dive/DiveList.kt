package no.synth.divelog.ui.dive

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.logbook.format.Format
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.units.UnitSystem

/** One dive summary row: site (or date) as the headline, depth emphasized. */
@Composable
fun DiveRow(dive: Dive, unitSystem: UnitSystem, onClick: () -> Unit, siteName: String? = null, source: String? = null) {
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

    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
