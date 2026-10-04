package no.synth.divelog.ui.stats

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.core.model.units.Units
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.components.EmptyState
import no.synth.divelog.ui.format.Format

@Composable
fun StatisticsSection(container: AppContainer, unitSystem: UnitSystem, dataVersion: Int) {
    val dives = remember(dataVersion) { container.dives.allDives() }
    if (dives.isEmpty()) {
        EmptyState("No dives yet. Statistics appear once you have logged some.", Icons.Outlined.BarChart)
        return
    }

    val totalSeconds = dives.sumOf { it.durationSeconds.toLong() }
    val deepest = dives.mapNotNull { it.maxDepthMm }.maxOrNull()
    val avgDepth = dives.mapNotNull { it.meanDepthMm }.ifEmpty { null }?.average()?.toInt()
    val perYear = dives
        .groupingBy { Format.year(it.startEpochSeconds, it.utcOffsetSeconds) }
        .eachCount()
        .toList()
        .sortedByDescending { it.first }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Dives", "${dives.size}", Modifier.weight(1f))
            StatTile("Total time", "${totalSeconds / 3600} h", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            StatTile("Deepest", Format.depth(deepest, unitSystem), Modifier.weight(1f))
            StatTile("Avg depth", Format.depth(avgDepth, unitSystem), Modifier.weight(1f))
        }

        Text("Dives per year", style = MaterialTheme.typography.titleMedium)
        val maxCount = perYear.maxOfOrNull { it.second } ?: 1
        perYear.forEach { (year, count) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("$year", Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium)
                Box(
                    Modifier
                        .fillMaxWidth(fraction = 0.1f + 0.8f * (count.toFloat() / maxCount.toFloat()))
                        .height(20.dp)
                        .background(MaterialTheme.colorScheme.primary),
                )
                Text(" $count", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun StatTile(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant, androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
            .padding(16.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineSmall)
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
