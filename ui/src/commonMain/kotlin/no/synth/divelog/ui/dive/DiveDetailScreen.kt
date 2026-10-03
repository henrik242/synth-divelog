package no.synth.divelog.ui.dive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import no.synth.divelog.core.model.DiveComputerRecord
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.format.Format

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiveDetailScreen(
    container: AppContainer,
    diveId: Long,
    unitSystem: UnitSystem,
    reloadKey: Int = 0,
    onEdit: () -> Unit = {},
) {
    val dive = remember(diveId, reloadKey) { container.dives.getDive(diveId) } ?: run {
        Text("Dive not found", Modifier.padding(16.dp)); return
    }
    val records = remember(diveId, reloadKey) { container.dives.recordsForDive(diveId) }
    val site = remember(diveId, reloadKey) { dive.siteId?.let { container.sites.site(it) } }
    val buddies = remember(diveId, reloadKey) { container.buddies.buddiesForDive(diveId) }

    var selectedRecord by remember(diveId) {
        mutableStateOf(
            records.firstOrNull { it.id == dive.primaryComputerRecordId } ?: records.firstOrNull(),
        )
    }
    val samples = remember(selectedRecord?.id) {
        selectedRecord?.let { container.dives.samplesForRecord(it.id) } ?: emptyList()
    }
    val events = remember(selectedRecord?.id) {
        selectedRecord?.let { container.dives.eventsForRecord(it.id) } ?: emptyList()
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = dive.number?.let { "Dive #$it" } ?: "Dive",
                style = MaterialTheme.typography.headlineSmall,
            )
            androidx.compose.material3.TextButton(onClick = onEdit) { Text("Edit") }
        }

        ProfileGraph(samples, events, unitSystem, Modifier.fillMaxWidth())

        if (records.size > 1) {
            Text("Computers", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(start = 16.dp, top = 8.dp))
            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                records.forEachIndexed { i, rec ->
                    FilterChip(
                        selected = selectedRecord?.id == rec.id,
                        onClick = { selectedRecord = rec },
                        label = { Text(recordLabel(rec, i, dive.primaryComputerRecordId)) },
                    )
                }
            }
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        SummaryRow("Date", Format.dateTime(dive.startEpochSeconds, dive.utcOffsetSeconds))
        SummaryRow("Duration", "${Format.duration(dive.durationSeconds)} min")
        SummaryRow("Max depth", Format.depth(dive.maxDepthMm, unitSystem))
        SummaryRow("Avg depth", Format.depth(dive.meanDepthMm, unitSystem))
        SummaryRow("Water temp", Format.temperature(dive.waterTempMk, unitSystem))
        SummaryRow("Site", site?.name ?: "-")
        SummaryRow("Buddies", if (buddies.isEmpty()) "-" else buddies.joinToString { it.name })
        dive.rating?.let { SummaryRow("Rating", "$it/5") }
        dive.visibility?.let { SummaryRow("Visibility", Format.depth(it, unitSystem)) }
        if (!dive.notes.isNullOrBlank()) {
            SummaryRow("Notes", dive.notes!!)
        }
    }
}

private fun recordLabel(record: DiveComputerRecord, index: Int, primaryId: Long?): String {
    val base = "Computer ${index + 1}"
    return if (record.id == primaryId) "$base (primary)" else base
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 16.dp))
    }
}
