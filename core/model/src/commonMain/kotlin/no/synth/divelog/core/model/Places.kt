package no.synth.divelog.core.model

/** An `id` of 0 marks an entity that has not been persisted yet. */
const val UNSAVED_ID: Long = 0L

data class Country(
    val id: Long = UNSAVED_ID,
    val name: String,
)

data class Place(
    val id: Long = UNSAVED_ID,
    val countryId: Long,
    val name: String,
)

data class Site(
    val id: Long = UNSAVED_ID,
    val placeId: Long,
    val name: String,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val notes: String? = null,
)
