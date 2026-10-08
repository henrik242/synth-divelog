package no.synth.divelog.core.formats

import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.GasMix
import no.synth.divelog.core.model.Sample

/** A whole logbook to export, or the result of an import. */
data class DiveLog(val dives: List<DiveEntry>)

/** One dive, flattened to just what the file formats carry (no database ids). */
data class DiveEntry(
    val number: Int? = null,
    val startEpochSeconds: Long,
    val utcOffsetSeconds: Int = 0,
    val durationSeconds: Int,
    val maxDepthMm: Int? = null,
    val meanDepthMm: Int? = null,
    val waterTempMk: Int? = null,
    val airTempMk: Int? = null,
    val notes: String? = null,
    val rating: Int? = null,
    /** Visibility as a distance, mm (UDDF, MacDive). */
    val visibility: Int? = null,
    /** Subsurface's 0..5 star visibility, not a distance. */
    val visibilityRating: Int? = null,
    val site: SiteRef? = null,
    val buddies: List<String> = emptyList(),
    val tags: List<String> = emptyList(),
    val gasMixes: List<GasMix> = emptyList(),
    val tanks: List<TankEntry> = emptyList(),
    val computers: List<ComputerEntry> = emptyList(),
)

/** A dive site. Formats with a flat location string split it best-effort. */
data class SiteRef(
    val name: String,
    val country: String? = null,
    val place: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
)

data class TankEntry(
    val index: Int,
    val volumeMl: Int? = null,
    val workingPressureMbar: Int? = null,
    val startPressureMbar: Int? = null,
    val endPressureMbar: Int? = null,
    val o2Permille: Int? = null,
    val hePermille: Int? = null,
)

/** One computer's recording of a dive. */
data class ComputerEntry(
    val model: String? = null,
    val maxDepthMm: Int? = null,
    val meanDepthMm: Int? = null,
    val waterTempMk: Int? = null,
    val airTempMk: Int? = null,
    val samples: List<Sample> = emptyList(),
    val events: List<Event> = emptyList(),
    /** The computer's serial number, when the log records one. */
    val serial: String? = null,
    /**
     * When this computer's recording starts, epoch seconds in the dive's frame (add the dive's
     * offset for the wall clock); null when it starts with the dive.
     */
    val startEpochSeconds: Long? = null,
    /** Length of this computer's recording; null when it is the dive's duration. */
    val durationSeconds: Int? = null,
)

/** A dive-log file format reader/writer over the domain-neutral [DiveLog]. */
interface DiveFormat {
    /** Stable id, e.g. "subsurface-xml". */
    val id: String

    /** User-facing name shown in import/export UI. */
    val displayName: String

    fun write(log: DiveLog): String

    fun read(text: String): DiveLog
}

/** Thrown when a file cannot be parsed as this format. */
class FormatException(message: String, cause: Throwable? = null) : Exception(message, cause)
