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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.format.Format

/** One dive summary row. */
@Composable
fun DiveRow(dive: Dive, unitSystem: UnitSystem, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(dive.number?.let { "#$it" } ?: "Dive", style = MaterialTheme.typography.titleMedium)
            Text(
                Format.dateTime(dive.startEpochSeconds, dive.utcOffsetSeconds),
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Format.depth(dive.maxDepthMm, unitSystem))
            Text("${Format.duration(dive.durationSeconds)} min", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** A list of dives that opens a read-only detail inline when one is tapped. */
@Composable
fun DiveListWithDetail(container: AppContainer, dives: List<Dive>, unitSystem: UnitSystem) {
    var openId by remember(dives) { mutableStateOf<Long?>(null) }
    val current = openId
    if (current != null) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
                TextButton(onClick = { openId = null }) { Text("< Back") }
            }
            DiveDetailScreen(container, current, unitSystem)
        }
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(dives) { dive ->
            DiveRow(dive, unitSystem, onClick = { openId = dive.id })
            HorizontalDivider()
        }
    }
}
