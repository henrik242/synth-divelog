package no.synth.divelog.core.db

import no.synth.divelog.core.model.Site
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SiteRepositoryTest {
    private val repo = SiteRepository(testDatabase())

    @Test
    fun countryPlaceSiteRoundTrip() {
        val norway = repo.addCountry("Norway")
        val oslo = repo.addPlace(norway, "Oslofjorden")
        val siteId = repo.addSite(Site(placeId = oslo, name = "Drøbak", latitude = 59.66, longitude = 10.63))

        assertEquals(listOf("Norway"), repo.countries().map { it.name })
        assertEquals(listOf("Oslofjorden"), repo.places(norway).map { it.name })
        val site = assertNotNull(repo.site(siteId))
        assertEquals("Drøbak", site.name)
        assertEquals(59.66, site.latitude)
    }

    @Test
    fun getOrCreateIsIdempotent() {
        val a = repo.getOrCreateSite("Norway", "Oslofjorden", "Drøbak")
        val b = repo.getOrCreateSite("Norway", "Oslofjorden", "Drøbak")
        assertEquals(a, b)
        assertEquals(1, repo.countries().size)
        assertEquals(1, repo.allSites().size)
    }

    @Test
    fun deletingCountryCascadesPlacesAndSites() {
        val norway = repo.getOrCreateSite("Norway", "Oslofjorden", "Drøbak")
        assertTrue(repo.allSites().isNotEmpty())

        val countryId = repo.countries().single().id
        repo.deleteCountry(countryId)

        assertTrue(repo.countries().isEmpty())
        assertTrue(repo.allSites().isEmpty())
        assertNull(repo.site(norway))
    }

    @Test
    fun updateSiteEditsFields() {
        val place = run {
            val c = repo.addCountry("Egypt")
            repo.addPlace(c, "Red Sea")
        }
        val id = repo.addSite(Site(placeId = place, name = "Thistlegorm"))
        repo.updateSite(assertNotNull(repo.site(id)).copy(notes = "wreck", latitude = 27.81))
        val updated = assertNotNull(repo.site(id))
        assertEquals("wreck", updated.notes)
        assertEquals(27.81, updated.latitude)
    }
}
