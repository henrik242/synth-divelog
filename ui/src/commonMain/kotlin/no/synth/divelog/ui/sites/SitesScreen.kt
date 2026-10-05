package no.synth.divelog.ui.sites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.backhandler.BackHandler
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.round
import no.synth.divelog.core.model.Country
import no.synth.divelog.core.model.Place
import no.synth.divelog.core.model.Site
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.components.BackHeader
import no.synth.divelog.ui.components.EmptyState
import no.synth.divelog.ui.dive.DiveListWithDetail

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SitesSection(container: AppContainer, unitSystem: UnitSystem, dataVersion: Int) {
    var country by remember(dataVersion) { mutableStateOf<Country?>(null) }
    var place by remember(dataVersion) { mutableStateOf<Place?>(null) }
    var site by remember(dataVersion) { mutableStateOf<Site?>(null) }
    var editing by remember(dataVersion) { mutableStateOf(false) }

    // Hardware back pops one drill level before the root handler switches sections.
    BackHandler(enabled = editing || site != null || place != null || country != null) {
        when {
            editing -> editing = false
            site != null -> site = null
            place != null -> place = null
            else -> country = null
        }
    }

    when {
        editing && site != null -> SiteEditScreen(
            container,
            site!!,
            onDone = { updated -> site = updated; editing = false },
            onCancel = { editing = false },
        )
        site != null -> SiteDetail(container, site!!, unitSystem, onEdit = { editing = true }, onBack = { site = null })
        place != null -> PlaceSites(container, place!!, onBack = { place = null }, onOpen = { site = it })
        country != null -> CountryPlaces(container, country!!, onBack = { country = null }, onOpen = { place = it })
        else -> Countries(container, onOpen = { country = it })
    }
}

@Composable
private fun Countries(container: AppContainer, onOpen: (Country) -> Unit) {
    val countries = remember { container.sites.countries() }
    if (countries.isEmpty()) {
        EmptyState("No dive sites yet. Add one when editing a dive.", Icons.Outlined.Place)
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        items(countries) { c ->
            RowItem(c.name) { onOpen(c) }
        }
    }
}

@Composable
private fun CountryPlaces(container: AppContainer, country: Country, onBack: () -> Unit, onOpen: (Place) -> Unit) {
    val places = remember(country.id) { container.sites.places(country.id) }
    Column(Modifier.fillMaxSize()) {
        BackHeader(country.name, onBack)
        LazyColumn(Modifier.fillMaxSize()) {
            items(places) { p -> RowItem(p.name) { onOpen(p) } }
        }
    }
}

@Composable
private fun PlaceSites(container: AppContainer, place: Place, onBack: () -> Unit, onOpen: (Site) -> Unit) {
    val sites = remember(place.id) { container.sites.sites(place.id) }
    Column(Modifier.fillMaxSize()) {
        BackHeader(place.name, onBack)
        LazyColumn(Modifier.fillMaxSize()) {
            items(sites) { s -> RowItem(s.name) { onOpen(s) } }
        }
    }
}

@Composable
private fun SiteDetail(
    container: AppContainer,
    site: Site,
    unitSystem: UnitSystem,
    onEdit: () -> Unit,
    onBack: () -> Unit,
) {
    val dives = remember(site.id) { container.dives.divesBySite(site.id) }
    Column(Modifier.fillMaxSize()) {
        BackHeader(site.name, onBack)
        if (site.latitude != null && site.longitude != null) {
            Text("${site.latitude}, ${site.longitude}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (!site.notes.isNullOrBlank()) {
            Text(site.notes!!, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onEdit, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Edit location") }
        Text("${dives.size} dive(s)", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
        DiveListWithDetail(container, dives, unitSystem)
    }
}

/** Edit a site's name, coordinate and notes. The map and the two fields set the
 * same latitude/longitude; either way of entering them works. */
@Composable
private fun SiteEditScreen(
    container: AppContainer,
    site: Site,
    onDone: (Site) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(site.id) { mutableStateOf(site.name) }
    var lat by remember(site.id) { mutableStateOf(site.latitude?.toString() ?: "") }
    var lon by remember(site.id) { mutableStateOf(site.longitude?.toString() ?: "") }
    var notes by remember(site.id) { mutableStateOf(site.notes ?: "") }

    fun save() {
        val updated = site.copy(
            name = name.trim().ifBlank { site.name },
            latitude = lat.trim().toDoubleOrNull(),
            longitude = lon.trim().toDoubleOrNull(),
            notes = notes.ifBlank { null },
        )
        container.sites.updateSite(updated)
        onDone(updated)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        BackHeader("Edit site", onCancel)

        OutlinedTextField(name, { name = it }, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                lat,
                { lat = it },
                label = { Text("Latitude") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                lon,
                { lon = it },
                label = { Text("Longitude") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                modifier = Modifier.weight(1f),
            )
        }

        SiteLocationMap(
            latitude = lat.trim().toDoubleOrNull(),
            longitude = lon.trim().toDoubleOrNull(),
            onPick = { pickedLat, pickedLon ->
                lat = round6(pickedLat).toString()
                lon = round6(pickedLon).toString()
            },
            modifier = Modifier.fillMaxWidth().height(280.dp),
        )

        OutlinedTextField(notes, { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth(), minLines = 3)

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { save() }, modifier = Modifier.weight(1f)) { Text("Save") }
            OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Cancel") }
        }
    }
}

/** Trim a map-picked coordinate to about a metre so the fields stay readable. */
private fun round6(value: Double): Double = round(value * 1_000_000) / 1_000_000

@Composable
private fun RowItem(text: String, onClick: () -> Unit) {
    Text(text, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp))
    HorizontalDivider()
}
