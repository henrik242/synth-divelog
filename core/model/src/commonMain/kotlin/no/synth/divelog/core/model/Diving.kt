package no.synth.divelog.core.model

/**
 * A logged dive. Summary fields are copied from the primary computer record; a
 * dive may hold several records (e.g. two computers worn together).
 *
 * Depths are millimetres, temperatures millikelvin, durations seconds. Start
 * time is epoch seconds in UTC with a separate local offset.
 */
data class Dive(
    val id: Long = UNSAVED_ID,
    val number: Int? = null,
    val startEpochSeconds: Long,
    val utcOffsetSeconds: Int,
    val durationSeconds: Int,
    val maxDepthMm: Int? = null,
    val meanDepthMm: Int? = null,
    val waterTempMk: Int? = null,
    val airTempMk: Int? = null,
    val notes: String? = null,
    val rating: Int? = null,
    val visibility: Int? = null,
    val siteId: Long? = null,
    val primaryComputerRecordId: Long? = null,
) {
    /** Exclusive end of the dive's time range, epoch seconds. */
    val endEpochSeconds: Long get() = startEpochSeconds + durationSeconds
}

/**
 * Summary of one computer's recording of a dive. The raw download blob lives in
 * storage and is fetched on demand; parsed samples and events are keyed by this
 * record's id.
 */
data class DiveComputerRecord(
    val id: Long = UNSAVED_ID,
    val diveId: Long,
    val deviceId: Long? = null,
    val startEpochSeconds: Long,
    val durationSeconds: Int,
    val maxDepthMm: Int? = null,
    val rawFormatId: String,
    val fingerprint: String,
) {
    val endEpochSeconds: Long get() = startEpochSeconds + durationSeconds
}

/** One profile point relative to the start of a computer record. */
data class Sample(
    val timeOffsetSeconds: Int,
    val depthMm: Int? = null,
    val temperatureMk: Int? = null,
    val ppO2Mbar: Int? = null,
    val ndlSeconds: Int? = null,
    val ceilingMm: Int? = null,
    val stopDepthMm: Int? = null,
    val stopTimeSeconds: Int? = null,
    val cnsPermille: Int? = null,
    val activeGasIndex: Int? = null,
    /** Cylinder pressures at this instant, keyed by tank index, millibar. */
    val tankPressuresMbar: Map<Int, Int> = emptyMap(),
)

enum class EventType {
    GAS_SWITCH,
    WARNING,
    BOOKMARK,
    DECO,
    SURFACE,
    ASCENT_RATE,
    OTHER,
    ;

    companion object {
        /** Parse a stored name, falling back to [OTHER] for anything unknown. */
        fun fromStored(name: String): EventType =
            entries.firstOrNull { it.name == name } ?: OTHER
    }
}

/** A discrete event at a point in a computer record's timeline. */
data class Event(
    val timeOffsetSeconds: Int,
    val type: EventType,
    val value: Long? = null,
)

/**
 * A dive as produced by a parser, ready to import. Carries the raw download so a
 * record can be re-parsed later, plus the parsed summary, samples and events.
 */
data class IncomingDive(
    val deviceId: Long? = null,
    val number: Int? = null,
    val startEpochSeconds: Long,
    val utcOffsetSeconds: Int,
    val durationSeconds: Int,
    val maxDepthMm: Int? = null,
    val meanDepthMm: Int? = null,
    val waterTempMk: Int? = null,
    val airTempMk: Int? = null,
    val rawData: ByteArray,
    val rawFormatId: String,
    val fingerprint: String,
    val samples: List<Sample> = emptyList(),
    val events: List<Event> = emptyList(),
    /** Gases breathed, in order of first use. Stored as the dive's tanks. */
    val gases: List<GasMix> = emptyList(),
) {
    val endEpochSeconds: Long get() = startEpochSeconds + durationSeconds
}
