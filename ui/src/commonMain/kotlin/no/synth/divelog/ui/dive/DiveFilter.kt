package no.synth.divelog.ui.dive

import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.state.ToggleableState
import no.synth.divelog.core.logbook.format.Format
import no.synth.divelog.core.model.Dive

/**
 * What dives can be filtered by. Years are ids too, the year itself. Country, place and site
 * make up one location filter, see [DiveFilter].
 */
enum class FacetKind(val title: String, val noun: String) {
    YEAR("Years", "Year"),
    COUNTRY("Countries", "Country"),
    PLACE("Places", "Place"),
    SITE("Sites", "Site"),
    BUDDY("Buddies", "Buddy"),
    TAG("Tags", "Tag"),
    GEAR("Gear", "Gear"),
}

private val LOCATION = listOf(FacetKind.COUNTRY, FacetKind.PLACE, FacetKind.SITE)

/**
 * What the dive list is narrowed to beyond the search: per [FacetKind] the ids picked. A dive
 * matches a kind when it has any of the picked ids, or none are picked; it must match every
 * kind. Country, place and site count as one kind: a dive in any picked country, place or
 * site matches it.
 */
data class DiveFilter(val picked: Map<FacetKind, Set<Long>> = emptyMap()) {
    val isEmpty: Boolean get() = picked.values.all { it.isEmpty() }

    operator fun get(kind: FacetKind): Set<Long> = picked[kind].orEmpty()

    fun with(kind: FacetKind, ids: Set<Long>): DiveFilter = DiveFilter(picked + (kind to ids))

    fun matches(dive: Dive, links: DiveLinks): Boolean = matchesExcept(null, dive, links)

    /** [matches] leaving [kind] out (all of the location for a location kind), for counting what picking in it would give. */
    fun matchesExcept(kind: FacetKind?, dive: Dive, links: DiveLinks): Boolean {
        val location = kind in LOCATION || LOCATION.all { this[it].isEmpty() } ||
            LOCATION.any { k -> links.of(k, dive).any { it in this[k] } }
        return location && FacetKind.entries.all { it == kind || it in LOCATION || matches(it, dive, links) }
    }

    private fun matches(kind: FacetKind, dive: Dive, links: DiveLinks): Boolean {
        val wanted = this[kind]
        return wanted.isEmpty() || links.of(kind, dive).any { it in wanted }
    }

    /** How many countries, places and sites are picked. */
    val locationCount: Int get() = LOCATION.sumOf { this[it].size }

    fun withoutLocation(): DiveFilter = DiveFilter(picked - LOCATION.toSet())

    /** Ticked when [path]'s last node or one above it is picked, partly when one below it is. */
    fun locationState(path: List<LocationNode>): ToggleableState = when {
        path.any { it.id in this[it.kind] } -> ToggleableState.On
        path.last().descendants().any { it.id in this[it.kind] } -> ToggleableState.Indeterminate
        else -> ToggleableState.Off
    }

    /**
     * Ticks or unticks [path]'s last node. Ticking picks it and drops the picks below it.
     * Unticking one that is ticked because a node above it is picked swaps that pick for the
     * node's siblings on the way down, so the rest stays ticked.
     */
    fun toggleLocation(path: List<LocationNode>): DiveFilter {
        val node = path.last()
        if (node.id in this[node.kind]) return with(node.kind, this[node.kind] - node.id)
        val above = path.indexOfFirst { it.id in this[it.kind] }
        if (above >= 0) {
            var f = with(path[above].kind, this[path[above].kind] - path[above].id)
            for (i in above until path.size - 1) {
                path[i].children.filter { it.id != path[i + 1].id }.forEach { f = f.with(it.kind, f[it.kind] + it.id) }
            }
            return f
        }
        var f = this
        node.descendants().forEach { f = f.with(it.kind, f[it.kind] - it.id) }
        return f.with(node.kind, f[node.kind] + node.id)
    }

    companion object {
        /** Per kind in order, the number of ids and then the ids. */
        val Saver: Saver<DiveFilter, LongArray> = Saver(
            save = { f -> FacetKind.entries.flatMap { listOf(f[it].size.toLong()) + f[it] }.toLongArray() },
            restore = { a ->
                var i = 0
                DiveFilter(
                    FacetKind.entries.associateWith {
                        val n = a[i++].toInt()
                        a.copyOfRange(i, i + n).toSet().also { i += n }
                    },
                )
            },
        )
    }
}

/**
 * The buddy, tag and gear ids on each dive that has any, the place of each site
 * ([sitePlaces]) and the country of each place ([placeCountries]), and each dive's local
 * [years], worked out once as they cost a date conversion; a dive missing there gets its
 * year worked out.
 */
data class DiveLinks(
    val buddies: Map<Long, Set<Long>> = emptyMap(),
    val tags: Map<Long, Set<Long>> = emptyMap(),
    val gear: Map<Long, Set<Long>> = emptyMap(),
    val sitePlaces: Map<Long, Long> = emptyMap(),
    val placeCountries: Map<Long, Long> = emptyMap(),
    val years: Map<Long, Long> = emptyMap(),
) {
    /** The ids [dive] has of [kind]. */
    fun of(kind: FacetKind, dive: Dive): Set<Long> = when (kind) {
        FacetKind.YEAR -> setOf(years[dive.id] ?: yearOf(dive))
        FacetKind.COUNTRY -> dive.siteId?.let(sitePlaces::get)?.let(placeCountries::get)?.let(::setOf).orEmpty()
        FacetKind.PLACE -> dive.siteId?.let(sitePlaces::get)?.let(::setOf).orEmpty()
        FacetKind.SITE -> dive.siteId?.let(::setOf).orEmpty()
        FacetKind.BUDDY -> buddies[dive.id].orEmpty()
        FacetKind.TAG -> tags[dive.id].orEmpty()
        FacetKind.GEAR -> gear[dive.id].orEmpty()
    }
}

fun yearOf(dive: Dive): Long = Format.year(dive.startEpochSeconds, dive.utcOffsetSeconds).toLong()

/** One thing to pick in a facet, with how many dives it would give alongside the other kinds' picks. */
data class FacetOption(val id: Long, val name: String, val count: Int)

/** One kind's [options] and the ids [picked]. */
data class Facet(val kind: FacetKind, val options: List<FacetOption>, val picked: Set<Long>)

/** A country, place or site in the location filter, with its dive count, and the places or sites in it. */
data class LocationNode(
    val kind: FacetKind,
    val id: Long,
    val name: String,
    val count: Int,
    val children: List<LocationNode> = emptyList(),
) {
    fun descendants(): List<LocationNode> = children.flatMap { listOf(it) + it.descendants() }
}

/** Every node of these trees, each before the nodes below it. */
fun List<LocationNode>.allNodes(): List<LocationNode> = flatMap { listOf(it) + it.descendants() }

private fun <T> List<T>.byCountThenName(count: (T) -> Int, name: (T) -> String) =
    sortedWith(compareByDescending(count).thenBy { name(it).lowercase() })

/**
 * The flat facets for [dives] (already narrowed by the search): per kind, everything at least
 * one of them has, plus what is picked. Counts take the other kinds' picks into account.
 * Years come newest first, the rest by count and then name. Kinds with nothing to offer are
 * left out, and so is the location, see [locations].
 */
fun DiveFilter.facets(dives: List<Dive>, links: DiveLinks, names: Map<FacetKind, Map<Long, String>>): List<Facet> =
    FacetKind.entries.filter { it !in LOCATION }.mapNotNull { kind ->
        val counts = HashMap<Long, Int>()
        val present = HashSet<Long>()
        for (dive in dives) {
            val ids = links.of(kind, dive)
            present += ids
            if (matchesExcept(kind, dive, links)) ids.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        }
        val options = (present + this[kind]).map { id ->
            val name = if (kind == FacetKind.YEAR) id.toString() else names[kind]?.get(id) ?: "(deleted)"
            FacetOption(id, name, counts[id] ?: 0)
        }
        val sorted = if (kind == FacetKind.YEAR) options.sortedByDescending { it.id } else options.byCountThenName({ it.count }, { it.name })
        sorted.takeIf { it.isNotEmpty() }?.let { Facet(kind, it, this[kind]) }
    }

/**
 * The countries [all] dives were in, with their places and those places' sites, plus whatever
 * is picked. Counts are of the [searched] dives and take the other kinds' picks into account;
 * each level is sorted by count and then name. The tree is of all dives so that unticking a
 * node under a picked one keeps the siblings the search hides.
 */
fun DiveFilter.locations(
    all: List<Dive>,
    searched: List<Dive>,
    links: DiveLinks,
    names: Map<FacetKind, Map<Long, String>>,
): List<LocationNode> {
    val counts = LOCATION.associateWith { HashMap<Long, Int>() }
    val sites = all.mapNotNullTo(HashSet()) { it.siteId }
    for (dive in searched) {
        if (dive.siteId == null) continue
        if (matchesExcept(FacetKind.SITE, dive, links)) {
            LOCATION.forEach { kind ->
                val byId = counts.getValue(kind)
                links.of(kind, dive).forEach { byId[it] = (byId[it] ?: 0) + 1 }
            }
        }
    }
    sites += this[FacetKind.SITE]
    val places = sites.mapNotNullTo(HashSet()) { links.sitePlaces[it] } + this[FacetKind.PLACE]
    val countries = places.mapNotNullTo(HashSet()) { links.placeCountries[it] } + this[FacetKind.COUNTRY]
    fun node(kind: FacetKind, id: Long, children: List<LocationNode> = emptyList()) = LocationNode(
        kind, id, names[kind]?.get(id) ?: "(deleted)", counts.getValue(kind)[id] ?: 0,
        children.byCountThenName({ it.count }, { it.name }),
    )
    fun placeNode(place: Long) = node(FacetKind.PLACE, place, sites.filter { links.sitePlaces[it] == place }.map { node(FacetKind.SITE, it) })
    // Places and sites whose country or place is gone sit at the top level.
    val roots = countries.map { country -> node(FacetKind.COUNTRY, country, places.filter { links.placeCountries[it] == country }.map(::placeNode)) } +
        places.filter { links.placeCountries[it] !in countries }.map(::placeNode) +
        sites.filter { links.sitePlaces[it] !in places }.map { node(FacetKind.SITE, it) }
    return roots.byCountThenName({ it.count }, { it.name })
}
