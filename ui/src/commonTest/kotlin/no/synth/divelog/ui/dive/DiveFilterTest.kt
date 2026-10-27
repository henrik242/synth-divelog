package no.synth.divelog.ui.dive

import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.state.ToggleableState
import no.synth.divelog.core.model.Dive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DiveFilterTest {
    // 2020-06-01 and 2021-06-01, UTC.
    private val y2020 = 1_590_969_600L
    private val y2021 = 1_622_505_600L

    private fun dive(id: Long, start: Long = y2020, siteId: Long? = null) =
        Dive(id = id, startEpochSeconds = start, utcOffsetSeconds = 0, durationSeconds = 0, siteId = siteId)

    private val links = DiveLinks(
        buddies = mapOf(1L to setOf(10L), 2L to setOf(11L)),
        gear = mapOf(1L to setOf(20L, 21L)),
    )

    @Test
    fun emptyMatchesEverything() {
        assertTrue(DiveFilter().isEmpty)
        assertTrue(DiveFilter().matches(dive(3), links))
    }

    @Test
    fun anyWithinAKindAllAcrossKinds() {
        val buddies = DiveFilter().with(FacetKind.BUDDY, setOf(10, 11))
        assertTrue(buddies.matches(dive(1), links))
        assertTrue(buddies.matches(dive(2), links))
        assertFalse(buddies.matches(dive(3), links))

        val buddyAndGear = buddies.with(FacetKind.GEAR, setOf(21))
        assertTrue(buddyAndGear.matches(dive(1), links))
        assertFalse(buddyAndGear.matches(dive(2), links))

        val site = DiveFilter().with(FacetKind.SITE, setOf(5))
        assertTrue(site.matches(dive(1, siteId = 5), links))
        assertFalse(site.matches(dive(1), links))
    }

    @Test
    fun severalYears() {
        val years = DiveFilter().with(FacetKind.YEAR, setOf(2020, 2021))
        assertTrue(years.matches(dive(1, start = y2020), links))
        assertTrue(years.matches(dive(1, start = y2021), links))
        assertFalse(DiveFilter().with(FacetKind.YEAR, setOf(2019)).matches(dive(1), links))
    }

    @Test
    fun countsIgnoreTheirOwnKindAndYearsComeNewestFirst() {
        val dives = listOf(dive(1, y2020), dive(2, y2021), dive(3, y2021))
        val names = mapOf(FacetKind.BUDDY to mapOf(10L to "Alex", 11L to "Sam"))
        val filter = DiveFilter().with(FacetKind.BUDDY, setOf(10)).with(FacetKind.YEAR, setOf(2020))
        val facets = filter.facets(dives, links, names).associateBy { it.kind }

        // Buddies counted within 2020: only dive 1, with Alex.
        assertEquals(listOf(FacetOption(10, "Alex", 1), FacetOption(11, "Sam", 0)), facets.getValue(FacetKind.BUDDY).options)
        // Years counted with Alex: one dive in 2020, none in 2021.
        assertEquals(listOf(FacetOption(2021, "2021", 0), FacetOption(2020, "2020", 1)), facets.getValue(FacetKind.YEAR).options)
        assertFalse(FacetKind.TAG in facets)
    }

    @Test
    fun countryAndPlaceGoThroughTheSite() {
        val placed = links.copy(sitePlaces = mapOf(5L to 50L, 6L to 60L), placeCountries = mapOf(50L to 500L, 60L to 500L))
        val egypt = DiveFilter().with(FacetKind.COUNTRY, setOf(500))
        assertTrue(egypt.matches(dive(1, siteId = 5), placed))
        assertTrue(egypt.matches(dive(1, siteId = 6), placed))
        assertFalse(egypt.matches(dive(1), placed))
        assertTrue(DiveFilter().with(FacetKind.PLACE, setOf(60)).matches(dive(1, siteId = 6), placed))
        assertFalse(DiveFilter().with(FacetKind.PLACE, setOf(60)).matches(dive(1, siteId = 5), placed))
    }

    @Test
    fun locationIsOneKind() {
        val placed = links.copy(sitePlaces = mapOf(5L to 50L, 6L to 60L), placeCountries = mapOf(50L to 500L, 60L to 600L))
        // Egypt or the site in Sweden: either matches.
        val filter = DiveFilter().with(FacetKind.COUNTRY, setOf(500)).with(FacetKind.SITE, setOf(6))
        assertTrue(filter.matches(dive(1, siteId = 5), placed))
        assertTrue(filter.matches(dive(1, siteId = 6), placed))
        assertFalse(filter.matches(dive(1), placed))
    }

    // Egypt (500) with Hurghada (50: sites 5, 7) and Dahab (51: site 8); Sweden (600) with Gothenburg (60: site 6).
    private val world = links.copy(
        sitePlaces = mapOf(5L to 50L, 7L to 50L, 8L to 51L, 6L to 60L),
        placeCountries = mapOf(50L to 500L, 51L to 500L, 60L to 600L),
    )
    private val worldNames = mapOf(
        FacetKind.COUNTRY to mapOf(500L to "Egypt", 600L to "Sweden"),
        FacetKind.PLACE to mapOf(50L to "Hurghada", 51L to "Dahab", 60L to "Gothenburg"),
        FacetKind.SITE to mapOf(5L to "Reef", 7L to "Wreck", 8L to "Canyon", 6L to "Quarry"),
    )
    private val worldDives = listOf(dive(1, siteId = 5), dive(2, siteId = 7), dive(3, siteId = 8), dive(4, siteId = 6), dive(5, siteId = 5))

    @Test
    fun locationTreeWithCounts() {
        val tree = DiveFilter().locations(worldDives, worldDives, world, worldNames)
        assertEquals(listOf("Egypt" to 4, "Sweden" to 1), tree.map { it.name to it.count })
        val egypt = tree.first()
        assertEquals(listOf("Hurghada" to 3, "Dahab" to 1), egypt.children.map { it.name to it.count })
        assertEquals(listOf("Reef" to 2, "Wreck" to 1), egypt.children.first().children.map { it.name to it.count })
    }

    @Test
    fun tickingAndUntickingInTheTree() {
        val egypt = DiveFilter().locations(worldDives, worldDives, world, worldNames).first()
        val hurghada = egypt.children.first()
        val reef = hurghada.children.first()

        // A site, then its country: the country takes over.
        var f = DiveFilter().toggleLocation(listOf(egypt, hurghada, reef))
        assertEquals(ToggleableState.Indeterminate, f.locationState(listOf(egypt)))
        f = f.toggleLocation(listOf(egypt))
        assertEquals(setOf(500L), f[FacetKind.COUNTRY])
        assertTrue(f[FacetKind.SITE].isEmpty())
        assertEquals(ToggleableState.On, f.locationState(listOf(egypt, hurghada, reef)))

        // Unticking the reef keeps the rest of Egypt: Dahab, and Hurghada's wreck.
        f = f.toggleLocation(listOf(egypt, hurghada, reef))
        assertTrue(f[FacetKind.COUNTRY].isEmpty())
        assertEquals(setOf(51L), f[FacetKind.PLACE])
        assertEquals(setOf(7L), f[FacetKind.SITE])
        assertEquals(ToggleableState.Off, f.locationState(listOf(egypt, hurghada, reef)))
        assertEquals(ToggleableState.Indeterminate, f.locationState(listOf(egypt)))
    }

    @Test
    fun untickingKeepsSiblingsTheSearchHides() {
        // The search shows only the Hurghada dives; Dahab's is hidden.
        val searched = worldDives.filter { it.siteId != 8L }
        val egypt = DiveFilter().locations(worldDives, searched, world, worldNames).first()
        val dahab = egypt.children.single { it.name == "Dahab" }
        assertEquals(0, dahab.count)

        val hurghada = egypt.children.single { it.name == "Hurghada" }
        val reef = hurghada.children.single { it.name == "Reef" }
        val f = DiveFilter().with(FacetKind.COUNTRY, setOf(500)).toggleLocation(listOf(egypt, hurghada, reef))
        assertEquals(setOf(51L), f[FacetKind.PLACE])
        assertEquals(setOf(7L), f[FacetKind.SITE])
    }

    @Test
    fun pickedButGoneStaysListed() {
        val filter = DiveFilter().with(FacetKind.TAG, setOf(7))
        val tags = filter.facets(listOf(dive(1)), links, emptyMap()).single { it.kind == FacetKind.TAG }
        assertEquals(listOf(FacetOption(7, "(deleted)", 0)), tags.options)
    }

    @Test
    fun savesAndRestores() {
        fun roundTrip(filter: DiveFilter): DiveFilter? =
            with(DiveFilter.Saver) { SaverScope { true }.save(filter) }?.let(DiveFilter.Saver::restore)
        fun picks(filter: DiveFilter?) = filter?.picked?.filterValues { it.isNotEmpty() }

        val filter = DiveFilter()
            .with(FacetKind.YEAR, setOf(2020))
            .with(FacetKind.SITE, setOf(1, 2))
            .with(FacetKind.GEAR, setOf(4, 5, 6))
        assertEquals(picks(filter), picks(roundTrip(filter)))
        assertEquals(picks(DiveFilter()), picks(roundTrip(DiveFilter())))
        val tagOnly = DiveFilter().with(FacetKind.TAG, setOf(9))
        assertEquals(picks(tagOnly), picks(roundTrip(tagOnly)))
    }

    @Test
    fun theYearIsTheDivesLocalOne() {
        // 2020-12-31 23:30 UTC is already 2021 at +02:00.
        val newYear = Dive(id = 1, startEpochSeconds = 1_609_457_400, utcOffsetSeconds = 7_200, durationSeconds = 0)
        assertEquals(2021L, yearOf(newYear))
        assertTrue(DiveFilter().with(FacetKind.YEAR, setOf(2021)).matches(newYear, links))
    }

    @Test
    fun countsTakeThePickedLocationIntoAccount() {
        val placed = world.copy(buddies = mapOf(1L to setOf(10L), 4L to setOf(10L)))
        val names = worldNames + (FacetKind.BUDDY to mapOf(10L to "Alex"))
        val egypt = DiveFilter().with(FacetKind.COUNTRY, setOf(500))
        // Alex dived dive 1 in Egypt and dive 4 in Sweden; with Egypt picked, one counts.
        val alex = egypt.facets(worldDives, placed, names).single { it.kind == FacetKind.BUDDY }.options.single()
        assertEquals(1, alex.count)
        // With Alex picked, Egypt has one dive and Sweden one.
        val withAlex = DiveFilter().with(FacetKind.BUDDY, setOf(10)).locations(worldDives, worldDives, placed, names)
        assertEquals(listOf(1, 1), withAlex.map { it.count })
    }

    @Test
    fun moreTickingRules() {
        val egypt = DiveFilter().locations(worldDives, worldDives, world, worldNames).first()
        val hurghada = egypt.children.single { it.name == "Hurghada" }
        val reef = hurghada.children.single { it.name == "Reef" }

        // A site picked on its own is simply unpicked.
        val site = DiveFilter().toggleLocation(listOf(egypt, hurghada, reef))
        assertTrue(site.toggleLocation(listOf(egypt, hurghada, reef)).isEmpty)

        // Unticking a place under a picked country keeps the country's other places.
        val withoutHurghada = DiveFilter().with(FacetKind.COUNTRY, setOf(500)).toggleLocation(listOf(egypt, hurghada))
        assertEquals(setOf(51L), withoutHurghada[FacetKind.PLACE])
        assertTrue(withoutHurghada[FacetKind.COUNTRY].isEmpty())

        // Ticking the country drops the place and site picked under it.
        val mixed = DiveFilter().with(FacetKind.PLACE, setOf(51)).with(FacetKind.SITE, setOf(5)).toggleLocation(listOf(egypt))
        assertEquals(setOf(500L), mixed[FacetKind.COUNTRY])
        assertTrue(mixed[FacetKind.PLACE].isEmpty())
        assertTrue(mixed[FacetKind.SITE].isEmpty())
    }

    @Test
    fun locationChipLabel() {
        val tree = DiveFilter().locations(worldDives, worldDives, world, worldNames)
        val egypt = tree.first()
        val hurghada = egypt.children.single { it.name == "Hurghada" }
        val reef = hurghada.children.single { it.name == "Reef" }
        assertEquals("Location", locationLabel(tree, DiveFilter()))
        assertEquals("Egypt", locationLabel(tree, DiveFilter().with(FacetKind.COUNTRY, setOf(500))))
        // Unticking the reef under a ticked Hurghada leaves its wreck: one of its two sites.
        val withoutReef = DiveFilter().with(FacetKind.PLACE, setOf(50)).toggleLocation(listOf(egypt, hurghada, reef))
        assertEquals("Wreck", locationLabel(tree, withoutReef))
        val twoPlaces = DiveFilter().with(FacetKind.PLACE, setOf(50, 51))
        assertEquals("Egypt · 2 of 2", locationLabel(tree, twoPlaces))
        val apart = DiveFilter().with(FacetKind.PLACE, setOf(50, 60))
        assertEquals("Location · 2", locationLabel(tree, apart))
    }

    @Test
    fun placesAndSitesWithoutTheirParentSitAtTheTop() {
        // Site 9 has no place; place 70 has no country.
        val orphans = world.copy(sitePlaces = world.sitePlaces + (10L to 70L))
        val dives = listOf(dive(1, siteId = 9), dive(2, siteId = 10))
        val tree = DiveFilter().locations(dives, dives, orphans, worldNames)
        assertEquals(setOf(FacetKind.SITE to 9L, FacetKind.PLACE to 70L), tree.map { it.kind to it.id }.toSet())
    }
}
