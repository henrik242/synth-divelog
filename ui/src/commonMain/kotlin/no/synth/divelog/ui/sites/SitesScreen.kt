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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlin.math.round
import no.synth.divelog.core.model.Country
import no.synth.divelog.core.model.Place
import no.synth.divelog.core.model.Site
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.components.CountryField
import no.synth.divelog.ui.components.EmptyState
import no.synth.divelog.ui.dive.DiveListWithDetail

/**
 * Renders a single Sites drill-in level from the navigation state owned by the caller
 * (SynthDivelogApp). The top-bar breadcrumb is the back affordance, so the levels no
 * longer draw their own headers. Callbacks move the lifted state to drill in.
 */
@Composable
fun SitesSection(
    container: AppContainer,
    unitSystem: UnitSystem,
    country: Country?,
    place: Place?,
    site: Site?,
    editing: Boolean,
    onCountryChange: (Country?) -> Unit,
    onPlaceChange: (Place?) -> Unit,
    onSiteChange: (Site?) -> Unit,
    onEditingChange: (Boolean) -> Unit,
) {
    when {
        editing && site != null -> SiteEditScreen(container, site)
        site != null -> SiteDetail(container, site, unitSystem, onEdit = { onEditingChange(true) })
        place != null -> PlaceSites(container, place, onOpen = { onSiteChange(it) })
        country != null -> CountryPlaces(container, country, onOpen = { onPlaceChange(it) })
        else -> Countries(
            container,
            onOpen = { onCountryChange(it) },
            onOpenSite = { id ->
                // Drill straight to the tapped pin's site, deriving its place and country.
                container.sites.site(id)?.let { s ->
                    val p = container.sites.place(s.placeId)
                    onCountryChange(p?.let { container.sites.country(it.countryId) })
                    onPlaceChange(p)
                    onSiteChange(s)
                }
            },
        )
    }
}

@Composable
private fun Countries(container: AppContainer, onOpen: (Country) -> Unit, onOpenSite: (Long) -> Unit) {
    val countries = remember { container.sites.countries() }
    val allSites = remember { container.sites.allSites() }
    if (countries.isEmpty()) {
        EmptyState("No dive sites yet. Add one when editing a dive.", Icons.Outlined.Place)
        return
    }
    Column(Modifier.fillMaxSize()) {
        // Overview map of every site that has a coordinate; tap a pin to open that site.
        // The map takes most of the space; the country list scrolls in the rest.
        SitesOverviewMap(
            allSites,
            onOpenSite,
            Modifier.fillMaxWidth().weight(2f).padding(16.dp),
        )
        LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
            items(countries) { c ->
                RowItem(c.name) { onOpen(c) }
            }
        }
    }
}

@Composable
private fun CountryPlaces(container: AppContainer, country: Country, onOpen: (Place) -> Unit) {
    val places = remember(country.id) { container.sites.places(country.id) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(places) { p -> RowItem(p.name) { onOpen(p) } }
    }
}

@Composable
private fun PlaceSites(container: AppContainer, place: Place, onOpen: (Site) -> Unit) {
    val sites = remember(place.id) { container.sites.sites(place.id) }
    LazyColumn(Modifier.fillMaxSize()) {
        items(sites) { s -> RowItem(s.name) { onOpen(s) } }
    }
}

@Composable
private fun SiteDetail(
    container: AppContainer,
    site: Site,
    unitSystem: UnitSystem,
    onEdit: () -> Unit,
) {
    val dives = remember(site.id) { container.dives.divesBySite(site.id) }
    Column(Modifier.fillMaxSize()) {
        if (site.latitude != null && site.longitude != null) {
            Text("${site.latitude}, ${site.longitude}", Modifier.padding(horizontal = 16.dp), style = MaterialTheme.typography.bodySmall)
            SiteLocationMap(
                latitude = site.latitude,
                longitude = site.longitude,
                interactive = false,
                modifier = Modifier.padding(16.dp).fillMaxWidth().height(220.dp),
            )
        }
        site.notes?.takeIf { it.isNotBlank() }?.let { notes ->
            Text(notes, Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        OutlinedButton(onClick = onEdit, modifier = Modifier.padding(horizontal = 16.dp)) { Text("Edit location") }
        Text("${dives.size} dive(s)", Modifier.padding(16.dp), style = MaterialTheme.typography.titleSmall)
        DiveListWithDetail(container, dives, unitSystem)
    }
}

/**
 * Edit a site's name, coordinate and notes. Every change is written straight to the
 * database, so there is no save step; leaving the screen (via the breadcrumb or back)
 * returns to the detail, which re-reads the saved site. While the site differs from how
 * it was opened, an "Undo changes" action restores the original. The map takes the
 * remaining space; the compact fields and the map set the same latitude/longitude, so
 * either way of entering them works.
 */
@Composable
private fun SiteEditScreen(
    container: AppContainer,
    site: Site,
) {
    val originalName = remember(site.id) { site.name }
    val originalLat = remember(site.id) { site.latitude?.toString() ?: "" }
    val originalLon = remember(site.id) { site.longitude?.toString() ?: "" }
    val originalNotes = remember(site.id) { site.notes ?: "" }

    var name by remember(site.id) { mutableStateOf(originalName) }
    var lat by remember(site.id) { mutableStateOf(originalLat) }
    var lon by remember(site.id) { mutableStateOf(originalLon) }
    var notes by remember(site.id) { mutableStateOf(originalNotes) }
    // The map is the main way to set the coordinate; the manual fields stay collapsed.
    var showCoordFields by remember(site.id) { mutableStateOf(false) }

    // Moving the site to another place/country changes its placeId; that is a deliberate
    // action (not keystroke autosave, which would create junk places while typing).
    var placeId by remember(site.id) { mutableStateOf(site.placeId) }
    var showMove by remember(site.id) { mutableStateOf(false) }
    var moveCountry by remember(site.id) { mutableStateOf("") }
    var movePlace by remember(site.id) { mutableStateOf("") }
    val place = remember(placeId) { container.sites.place(placeId) }
    val countryName = remember(placeId) { place?.let { container.sites.country(it.countryId)?.name } ?: "" }
    val placeName = place?.name ?: ""

    fun current(): Site = site.copy(
        placeId = placeId,
        name = name.trim().ifBlank { site.name },
        latitude = lat.trim().toDoubleOrNull(),
        longitude = lon.trim().toDoubleOrNull(),
        notes = notes.ifBlank { null },
    )

    fun persist() = container.sites.updateSite(current())

    val hasEdits = name != originalName || lat != originalLat || lon != originalLon ||
        notes != originalNotes || placeId != site.placeId

    fun undo() {
        name = originalName; lat = originalLat; lon = originalLon; notes = originalNotes
        placeId = site.placeId
        persist()
    }

    Column(Modifier.fillMaxSize()) {
        if (hasEdits) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { undo() }) { Text("Undo changes") }
            }
        }

        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                name,
                { name = it; persist() },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            val locationSummary = listOf(countryName, placeName).filter { it.isNotBlank() }
                .joinToString(" / ").ifBlank { "-" }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Location: $locationSummary",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    moveCountry = countryName; movePlace = placeName; showMove = !showMove
                }) { Text(if (showMove) "Hide" else "Move") }
            }
            if (showMove) {
                CountryField(moveCountry, { moveCountry = it }, Modifier.fillMaxWidth())
                OutlinedTextField(
                    movePlace,
                    { movePlace = it },
                    label = { Text("Place") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    enabled = moveCountry.isNotBlank() && movePlace.isNotBlank(),
                    onClick = {
                        placeId = container.sites.getOrCreatePlace(moveCountry.trim(), movePlace.trim())
                        persist()
                        showMove = false
                    },
                ) { Text("Move here") }
            }

            val coordSummary = run {
                val la = lat.trim().toDoubleOrNull()
                val lo = lon.trim().toDoubleOrNull()
                if (la != null && lo != null) "${round6(la)}, ${round6(lo)}" else "tap the map or set manually"
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Coordinate: $coordSummary",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { showCoordFields = !showCoordFields }) {
                    Text(if (showCoordFields) "Hide" else "Edit")
                }
            }
            if (showCoordFields) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        lat,
                        { lat = it; persist() },
                        label = { Text("Latitude") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        lon,
                        { lon = it; persist() },
                        label = { Text("Longitude") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        // The map fills the space the fixed fields and the old save row used to take.
        SiteLocationMap(
            latitude = lat.trim().toDoubleOrNull(),
            longitude = lon.trim().toDoubleOrNull(),
            onPick = { pickedLat, pickedLon ->
                lat = round6(pickedLat).toString()
                lon = round6(pickedLon).toString()
                persist()
            },
            modifier = Modifier.fillMaxWidth().weight(1f).padding(16.dp),
        )

        OutlinedTextField(
            notes,
            { notes = it; persist() },
            label = { Text("Notes") },
            minLines = 2,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        )
    }
}

/** Trim a map-picked coordinate to about a metre so the fields stay readable. */
private fun round6(value: Double): Double = round(value * 1_000_000) / 1_000_000

@Composable
private fun RowItem(text: String, onClick: () -> Unit) {
    Text(text, Modifier.fillMaxWidth().clickable(onClick = onClick).padding(16.dp))
    HorizontalDivider()
}
