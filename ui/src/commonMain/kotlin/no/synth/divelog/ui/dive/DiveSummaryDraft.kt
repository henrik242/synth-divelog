package no.synth.divelog.ui.dive

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import no.synth.divelog.core.model.Dive
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.core.model.units.Units
import no.synth.divelog.core.model.units.ZERO_CELSIUS_MK
import no.synth.divelog.ui.components.dateOfMillis
import no.synth.divelog.ui.components.parseDate
import no.synth.divelog.ui.components.parseDecimal
import no.synth.divelog.ui.components.trimmedDecimal
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * The time and depth fields of a dive without a computer record, as typed. Date is
 * YYYY-MM-DD and time HH:MM on the dive's wall clock; duration is minutes or m:ss.
 * A field that does not parse keeps the dive's value; a blank depth or temperature clears it.
 */
internal data class DiveSummaryDraft(
    val date: String,
    val time: String,
    val duration: String,
    val maxDepth: String,
    val meanDepth: String,
    val waterTemp: String,
) {
    fun dateValid() = parseDate(date) != null
    fun timeValid() = parseTime(time) != null
    fun durationValid() = parseDuration(duration) != null
    fun maxDepthValid() = maxDepth.isBlank() || parseDecimal(maxDepth) != null
    fun meanDepthValid() = meanDepth.isBlank() || parseDecimal(meanDepth) != null
    fun waterTempValid() = waterTemp.isBlank() || parseDecimal(waterTemp) != null

    /** The time as typed, or null when it does not parse. */
    fun timeOfDay(): LocalTime? = parseTime(time)

    /** This draft with the time the picker gave. */
    fun withTime(hour: Int, minute: Int): DiveSummaryDraft = copy(time = "${two(hour)}:${two(minute)}")

    /** The date as UTC midnight in epoch milliseconds, as the date picker takes it; null when it does not parse. */
    fun dateMillis(): Long? = parseDate(date)?.atStartOfDayIn(TimeZone.UTC)?.toEpochMilliseconds()

    /** This draft with the date the picker gave, UTC midnight in epoch milliseconds. */
    fun withDateMillis(millis: Long): DiveSummaryDraft =
        copy(date = dateOfMillis(millis).toString())

    /** [dive] with every field that parses taken from this draft. */
    fun applyTo(dive: Dive, system: UnitSystem): Dive {
        val wall = wallClock(dive)
        val shown = of(dive, system)
        // An unchanged date and time keep the start, seconds and all.
        val start = if (date == shown.date && time == shown.time) {
            dive.startEpochSeconds
        } else {
            val day = parseDate(date) ?: wall.date
            val clock = parseTime(time) ?: wall.time
            LocalDateTime(day, clock).toInstant(TimeZone.UTC).epochSeconds - dive.utcOffsetSeconds
        }
        return dive.copy(
            startEpochSeconds = start,
            durationSeconds = parseDuration(duration) ?: dive.durationSeconds,
            maxDepthMm = depthMm(maxDepth, dive.maxDepthMm, system),
            meanDepthMm = depthMm(meanDepth, dive.meanDepthMm, system),
            waterTempMk = temperatureMk(waterTemp, dive.waterTempMk, system),
        )
    }

    companion object {
        fun of(dive: Dive, system: UnitSystem): DiveSummaryDraft {
            val wall = wallClock(dive)
            return DiveSummaryDraft(
                date = wall.date.toString(),
                time = "${two(wall.hour)}:${two(wall.minute)}",
                duration = durationText(dive.durationSeconds),
                maxDepth = dive.maxDepthMm?.let { depthText(it, system) } ?: "",
                meanDepth = dive.meanDepthMm?.let { depthText(it, system) } ?: "",
                waterTemp = dive.waterTempMk?.let { temperatureText(it, system) } ?: "",
            )
        }

        private fun wallClock(dive: Dive): LocalDateTime =
            Instant.fromEpochSeconds(dive.startEpochSeconds + dive.utcOffsetSeconds).toLocalDateTime(TimeZone.UTC)

        private fun two(v: Int) = v.toString().padStart(2, '0')

        private fun parseTime(text: String): LocalTime? {
            val (h, m) = text.trim().split(':').takeIf { it.size == 2 }?.map { it.toIntOrNull() } ?: return null
            if (h == null || m == null || h !in 0..23 || m !in 0..59) return null
            return LocalTime(h, m)
        }

        /** "45" or "45:30"; whole minutes are shown without seconds. */
        private fun durationText(seconds: Int): String =
            if (seconds % 60 == 0) "${seconds / 60}" else "${seconds / 60}:${two(seconds % 60)}"

        private fun parseDuration(text: String): Int? {
            val parts = text.trim().split(':')
            val minutes = parts[0].toIntOrNull()?.takeIf { it >= 0 } ?: return null
            val seconds = when (parts.size) {
                1 -> 0
                2 -> parts[1].takeIf { it.length == 2 }?.toIntOrNull()?.takeIf { it in 0..59 } ?: return null
                else -> return null
            }
            return minutes * 60 + seconds
        }

        private fun depthText(mm: Int, system: UnitSystem) = trimmedDecimal(Units.depth(mm, system).value)

        // An unchanged text keeps the stored value, so showing it rounded does not change it.
        private fun depthMm(text: String, saved: Int?, system: UnitSystem): Int? {
            if (text.isBlank()) return null
            if (saved != null && text == depthText(saved, system)) return saved
            val v = parseDecimal(text)?.takeIf { it >= 0 } ?: return saved
            // One unit of the system in millimetres: 1 m, or 1 ft.
            return (v * 1_000 / Units.depth(1_000, system).value).roundToInt()
        }

        private fun temperatureText(mk: Int, system: UnitSystem) = trimmedDecimal(Units.temperature(mk, system).value)

        private fun temperatureMk(text: String, saved: Int?, system: UnitSystem): Int? {
            if (text.isBlank()) return null
            if (saved != null && text == temperatureText(saved, system)) return saved
            val v = parseDecimal(text) ?: return saved
            val celsius = if (system == UnitSystem.METRIC) v else (v - 32) * 5 / 9
            return ZERO_CELSIUS_MK + (celsius * 1_000).roundToInt()
        }
    }
}
