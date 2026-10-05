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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.text.KeyboardOptions
import no.synth.divelog.core.model.Place
import no.synth.divelog.core.model.Site
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.components.CountryField
import no.synth.divelog.ui.components.SiteField
import no.synth.divelog.ui.components.SiteOption
import kotlin.math.roundToLong

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

    // Resolve a site's place and country for the picker detail line and the selected-site card.
    val countriesById = remember(diveId) { container.sites.countries().associateBy { it.id } }
    val placeCache = remember(diveId) { mutableMapOf<Long, Place?>() }
    fun placeOf(placeId: Long): Place? = placeCache.getOrPut(placeId) { container.sites.place(placeId) }
    fun locationLabel(placeId: Long): String {
        val place = placeOf(placeId)
        return listOfNotNull(place?.name, place?.let { countriesById[it.countryId]?.name }).joinToString(", ")
    }
    val siteOptions = existingSites.map { SiteOption(it.id, it.name, locationLabel(it.placeId)) }

    var number by remember { mutableStateOf(dive.number?.toString() ?: "") }
    var notes by remember { mutableStateOf(dive.notes ?: "") }
    var selectedSiteId by remember { mutableStateOf(dive.siteId) }
    var newCountry by remember { mutableStateOf("") }
    var newPlace by remember { mutableStateOf("") }
    var newSite by remember { mutableStateOf("") }
    var addingSite by remember { mutableStateOf(false) }
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

    val focusManager = LocalFocusManager.current
    Column(
        Modifier.fillMaxSize()
            // Tab / Shift-Tab move between fields instead of being typed or trapped in one.
            // The preview pass runs on this ancestor before the focused field handles the key.
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Tab) {
                    focusManager.moveFocus(if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next)
                    true
                } else {
                    false
                }
            }
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
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

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Site", style = MaterialTheme.typography.titleSmall)
            SiteField(
                options = siteOptions,
                selectedId = selectedSiteId,
                onSelect = { selectedSiteId = it },
                modifier = Modifier.fillMaxWidth(),
            )
            existingSites.firstOrNull { it.id == selectedSiteId }?.let { site ->
                val detail = buildList {
                    locationLabel(site.placeId).takeIf { it.isNotEmpty() }?.let { add(it) }
                    coordinateLabel(site.latitude, site.longitude)?.let { add(it) }
                    site.notes?.takeIf { it.isNotBlank() }?.let { add(it) }
                }
                if (detail.isNotEmpty()) {
                    Text(
                        detail.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            if (addingSite) {
                CountryField(newCountry, { newCountry = it }, Modifier.fillMaxWidth().padding(top = 8.dp))
                OutlinedTextField(newPlace, { newPlace = it }, label = { Text("Place") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(newSite, { newSite = it }, label = { Text("Site name") }, modifier = Modifier.fillMaxWidth())
                TextButton(onClick = { addingSite = false; newCountry = ""; newPlace = ""; newSite = "" }) {
                    Text("Cancel new site")
                }
            } else {
                TextButton(onClick = { addingSite = true }) { Text("Add a new site") }
            }
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

/** A site's coordinate as "lat, lon" at about a metre's precision, or null if not set. */
private fun coordinateLabel(latitude: Double?, longitude: Double?): String? {
    if (latitude == null || longitude == null) return null
    return "${round5(latitude)}, ${round5(longitude)}"
}

private fun round5(value: Double): Double = (value * 100_000).roundToLong() / 100_000.0
