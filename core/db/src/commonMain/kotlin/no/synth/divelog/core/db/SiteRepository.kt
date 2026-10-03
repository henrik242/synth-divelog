package no.synth.divelog.core.db

import no.synth.divelog.core.db.sql.DiveDatabase
import no.synth.divelog.core.model.Country
import no.synth.divelog.core.model.Place
import no.synth.divelog.core.model.Site

/** Dive sites, organised as country -> place -> site. */
class SiteRepository(private val db: DiveDatabase) {
    private val q = db.placesQueries

    // Countries
    fun addCountry(name: String): Long = db.transactionWithResult {
        q.insertCountry(name)
        q.lastInsertRowId().executeAsOne()
    }

    fun getOrCreateCountry(name: String): Long = db.transactionWithResult {
        q.selectCountryByName(name).executeAsOneOrNull()?.id ?: run {
            q.insertCountry(name)
            q.lastInsertRowId().executeAsOne()
        }
    }

    fun countries(): List<Country> = q.selectAllCountries().executeAsList().map { it.toDomain() }

    fun country(id: Long): Country? = q.selectCountryById(id).executeAsOneOrNull()?.toDomain()

    fun renameCountry(id: Long, name: String) = q.updateCountry(name, id)

    fun deleteCountry(id: Long) = q.deleteCountry(id)

    // Places
    fun addPlace(countryId: Long, name: String): Long = db.transactionWithResult {
        q.insertPlace(countryId, name)
        q.lastInsertRowId().executeAsOne()
    }

    fun places(countryId: Long): List<Place> =
        q.selectPlacesByCountry(countryId).executeAsList().map { it.toDomain() }

    fun place(id: Long): Place? = q.selectPlaceById(id).executeAsOneOrNull()?.toDomain()

    fun updatePlace(place: Place) = q.updatePlace(place.countryId, place.name, place.id)

    fun deletePlace(id: Long) = q.deletePlace(id)

    // Sites
    fun addSite(site: Site): Long = db.transactionWithResult {
        q.insertSite(site.placeId, site.name, site.latitude, site.longitude, site.notes)
        q.lastInsertRowId().executeAsOne()
    }

    fun sites(placeId: Long): List<Site> =
        q.selectSitesByPlace(placeId).executeAsList().map { it.toDomain() }

    fun allSites(): List<Site> = q.selectAllSites().executeAsList().map { it.toDomain() }

    fun site(id: Long): Site? = q.selectSiteById(id).executeAsOneOrNull()?.toDomain()

    fun updateSite(site: Site) =
        q.updateSite(site.placeId, site.name, site.latitude, site.longitude, site.notes, site.id)

    fun deleteSite(id: Long) = q.deleteSite(id)

    /** Resolve or build a full country/place/site path, returning the site id. */
    fun getOrCreateSite(country: String, place: String, site: String): Long =
        db.transactionWithResult {
            val countryId = q.selectCountryByName(country).executeAsOneOrNull()?.id ?: run {
                q.insertCountry(country)
                q.lastInsertRowId().executeAsOne()
            }
            val placeId = q.selectPlacesByCountry(countryId).executeAsList()
                .firstOrNull { it.name == place }?.id ?: run {
                q.insertPlace(countryId, place)
                q.lastInsertRowId().executeAsOne()
            }
            q.selectSitesByPlace(placeId).executeAsList().firstOrNull { it.name == site }?.id ?: run {
                q.insertSite(placeId, site, null, null, null)
                q.lastInsertRowId().executeAsOne()
            }
        }
}
