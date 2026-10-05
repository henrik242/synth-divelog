package no.synth.divelog.ui.dive

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import no.synth.divelog.core.model.Site
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.components.CountryField

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DiveEditScreen(
    container: AppContainer,
    diveId: Long,
    unitSystem: UnitSystem,
    onDone: () -> Unit,
    onCancel: () -> Unit,
) {
    val dive = remember(diveId) { container.dives.getDive(diveId) } ?: run { onCancel(); return }
    val existingSites = remember(diveId) { container.sites.allSites() }
    val allBuddies = remember(diveId) { container.buddies.all() }
    val linkedBuddyIds = remember(diveId) { container.buddies.buddiesForDive(diveId).map { it.id }.toSet() }

    var number by remember { mutableStateOf(dive.number?.toString() ?: "") }
    var notes by remember { mutableStateOf(dive.notes ?: "") }
    var selectedSiteId by remember { mutableStateOf(dive.siteId) }
    var newCountry by remember { mutableStateOf("") }
    var newPlace by remember { mutableStateOf("") }
    var newSite by remember { mutableStateOf("") }
    var buddySelection by remember { mutableStateOf(linkedBuddyIds) }
    var newBuddy by remember { mutableStateOf("") }
    var createdBuddies by remember { mutableStateOf(listOf<String>()) }

    fun save() {
        val siteId = if (newSite.isNotBlank() && newCountry.isNotBlank() && newPlace.isNotBlank()) {
            container.sites.getOrCreateSite(newCountry.trim(), newPlace.trim(), newSite.trim())
        } else {
            selectedSiteId
        }
        container.dives.updateDive(
            dive.copy(
                number = number.trim().toIntOrNull(),
                notes = notes.ifBlank { null },
                siteId = siteId,
            ),
        )
        val newIds = createdBuddies.filter { it.isNotBlank() }.map { container.buddies.add(it.trim()) }
        val finalIds = buddySelection + newIds
        (finalIds - linkedBuddyIds).forEach { container.buddies.linkToDive(diveId, it) }
        (linkedBuddyIds - finalIds).forEach { container.buddies.unlinkFromDive(diveId, it) }
        onDone()
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Edit dive", style = MaterialTheme.typography.headlineSmall)

        OutlinedTextField(
            number,
            { number = it.filter { c -> c.isDigit() } },
            label = { Text("Dive number") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
        )

        OutlinedTextField(
            notes,
            { notes = it },
            label = { Text("Notes") },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
        )

        Column {
            Text("Site", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                existingSites.forEach { site: Site ->
                    FilterChip(
                        selected = selectedSiteId == site.id,
                        onClick = { selectedSiteId = if (selectedSiteId == site.id) null else site.id },
                        label = { Text(site.name) },
                    )
                }
            }
            Text("Or add a new site", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            CountryField(newCountry, { newCountry = it }, Modifier.fillMaxWidth())
            OutlinedTextField(newPlace, { newPlace = it }, label = { Text("Place") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(newSite, { newSite = it }, label = { Text("Site name") }, modifier = Modifier.fillMaxWidth())
        }

        Column {
            Text("Buddies", style = MaterialTheme.typography.titleSmall)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                allBuddies.forEach { buddy ->
                    FilterChip(
                        selected = buddy.id in buddySelection,
                        onClick = {
                            buddySelection = if (buddy.id in buddySelection) buddySelection - buddy.id else buddySelection + buddy.id
                        },
                        label = { Text(buddy.name) },
                    )
                }
                createdBuddies.forEach { name -> FilterChip(selected = true, onClick = {}, label = { Text(name) }) }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(newBuddy, { newBuddy = it }, label = { Text("Add buddy") }, modifier = Modifier.weight(1f))
                OutlinedButton(
                    onClick = { if (newBuddy.isNotBlank()) { createdBuddies = createdBuddies + newBuddy.trim(); newBuddy = "" } },
                ) { Text("Add") }
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save() }, modifier = Modifier.weight(1f)) { Text("Save") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
        }
    }
}
