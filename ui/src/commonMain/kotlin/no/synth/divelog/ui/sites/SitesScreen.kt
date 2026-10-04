package no.synth.divelog.ui.sites

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
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
import no.synth.divelog.core.model.Country
import no.synth.divelog.core.model.Place
import no.synth.divelog.core.model.Site
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.components.BackHeader
import no.synth.divelog.ui.components.EmptyState
import no.synth.divelog.ui.dive.DiveListWithDetail

@Composable
fun SitesSection(container: AppContainer, unitSystem: UnitSystem, dataVersion: Int) {
    var country by remember(dataVersion) { mutableStateOf<Country?>(null) }
    var place by remember(dataVersion) { mutableStateOf<Place?>(null) }
    var site by remember(dataVersion) { mutableStateOf<Site?>(null) }

    when {
        site != null -> SiteDetail(container, site!!, unitSystem, onBack = { site = null })
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
private fun SiteDetail(container: AppContainer, site: Site, unitSystem: UnitSystem, onBack: () -> Unit) {
    val dives = remember(site.id) { container.dives.divesBySite(site.id) }
    Column(Modifier.fillMaxSize()) {
        BackHeader(site.name, onBack)
        if (site.latitude != null && site.longitude != null) {
            Text("${site.latitude}, ${site.longitude}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
        }
        if (!site.notes.isNullOrBlank()) {
            Text(site.notes!!, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Text("${dives.size} dive(s)", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
        DiveListWithDetail(container, dives, unitSystem)
    }
}

@Composable
private fun RowItem(text: String, onClick: () -> Unit) {
    Text(text, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp))
    HorizontalDivider()
}

