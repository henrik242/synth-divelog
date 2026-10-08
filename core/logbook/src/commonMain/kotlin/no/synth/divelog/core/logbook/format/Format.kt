package no.synth.divelog.core.logbook.format

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

    /** "Air", "Oxygen", "EAN32" or, with helium or below 21 % O2, "18/45" and "10/0". */
    fun gasName(o2Permille: Int, hePermille: Int): String {
        fun pct(permille: Int) = oneDecimal(permille / 10.0).removeSuffix(".0")
        return when {
            hePermille > 0 || o2Permille < 210 -> "${pct(o2Permille)}/${pct(hePermille)}"
            o2Permille == 210 -> "Air"
            o2Permille == 1_000 -> "Oxygen"
            else -> "EAN${pct(o2Permille)}"
        }
    }

    /** "210 bar" or "3046 psi". */
    fun pressure(mbar: Int, system: UnitSystem): String {
        val p = Units.pressure(mbar, system)
        return "${kotlin.math.round(p.value).toLong()} ${p.unit}"
    }

    /** A tank's size: "12 L", or "80 cuft" in imperial when its working pressure is known. See [Units.tankSize]. */
    fun tankSize(ml: Int, workingPressureMbar: Int?, system: UnitSystem): String {
        val v = Units.tankSize(ml, workingPressureMbar, system)
        return "${oneDecimal(v.value).removeSuffix(".0")} ${v.unit}"
    }

    private fun two(v: Int): String = if (v < 10) "0$v" else "$v"

    fun oneDecimal(v: Double): String {
        val rounded = kotlin.math.round(v * 10).toLong()
        // Sign apart, so -0.2 does not lose it to a whole part of 0.
        val sign = if (rounded < 0) "-" else ""
        val abs = kotlin.math.abs(rounded)
        return "$sign${abs / 10}.${abs % 10}"
    }
}
