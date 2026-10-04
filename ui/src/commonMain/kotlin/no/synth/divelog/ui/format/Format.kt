package no.synth.divelog.ui.format

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.core.model.units.Units
import kotlin.time.Instant

/** Display formatting for the UI. Storage stays in fixed units; this is view-only. */
object Format {
    private val months = listOf(
        "Jan", "Feb", "Mar", "Apr", "May", "Jun",
        "Jul", "Aug", "Sep", "Oct", "Nov", "Dec",
    )

    /** Wall-clock date/time for a dive, honouring its stored UTC offset. */
    private fun wallClock(epochSeconds: Long, utcOffsetSeconds: Int): LocalDateTime =
        Instant.fromEpochSeconds(epochSeconds + utcOffsetSeconds).toLocalDateTime(TimeZone.UTC)

    private val fullMonths = listOf(
        "January", "February", "March", "April", "May", "June",
        "July", "August", "September", "October", "November", "December",
    )

    fun year(epochSeconds: Long, utcOffsetSeconds: Int): Int =
        wallClock(epochSeconds, utcOffsetSeconds).year

    /** Month and year, e.g. "August 2026", for list section headers. */
    fun monthYear(epochSeconds: Long, utcOffsetSeconds: Int): String {
        val t = wallClock(epochSeconds, utcOffsetSeconds)
        return "${fullMonths[t.month.ordinal]} ${t.year}"
    }

    fun date(epochSeconds: Long, utcOffsetSeconds: Int): String {
        val t = wallClock(epochSeconds, utcOffsetSeconds)
        return "${t.day} ${months[t.month.ordinal]} ${t.year}"
    }

    fun time(epochSeconds: Long, utcOffsetSeconds: Int): String {
        val t = wallClock(epochSeconds, utcOffsetSeconds)
        return "${two(t.hour)}:${two(t.minute)}"
    }

    fun dateTime(epochSeconds: Long, utcOffsetSeconds: Int): String =
        "${date(epochSeconds, utcOffsetSeconds)} ${time(epochSeconds, utcOffsetSeconds)}"

    fun duration(seconds: Int): String {
        val m = Units.durationMinutesPart(seconds)
        val s = Units.durationSecondsPart(seconds)
        return "$m:${two(s)}"
    }

    fun depth(mm: Int?, system: UnitSystem): String {
        if (mm == null) return "-"
        val m = Units.depth(mm, system)
        return "${oneDecimal(m.value)} ${m.unit}"
    }

    fun temperature(mk: Int?, system: UnitSystem): String {
        if (mk == null) return "-"
        val t = Units.temperature(mk, system)
        return "${oneDecimal(t.value)} ${t.unit}"
    }

    private fun two(v: Int): String = if (v < 10) "0$v" else "$v"

    private fun oneDecimal(v: Double): String {
        val rounded = kotlin.math.round(v * 10).toLong()
        val whole = rounded / 10
        val frac = kotlin.math.abs(rounded % 10)
        return "$whole.$frac"
    }
}
