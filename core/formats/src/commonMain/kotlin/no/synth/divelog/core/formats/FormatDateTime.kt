package no.synth.divelog.core.formats

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Dive start times are stored as epoch seconds plus a UTC offset. Subsurface, MacDive and
 * Shearwater write the local wall clock with no zone; UDDF writes ISO 8601, which may carry
 * one. A time with no zone is kept as the wall clock with a zero offset.
 */
internal object FormatDateTime {
    private fun wallClock(epochSeconds: Long, utcOffsetSeconds: Int): LocalDateTime =
        Instant.fromEpochSeconds(epochSeconds + utcOffsetSeconds).toLocalDateTime(TimeZone.UTC)

    private fun p2(v: Int) = v.toString().padStart(2, '0')

    fun date(epochSeconds: Long, utcOffsetSeconds: Int): String {
        val t = wallClock(epochSeconds, utcOffsetSeconds)
        return "${t.year}-${p2(t.month.ordinal + 1)}-${p2(t.day)}"
    }

    fun time(epochSeconds: Long, utcOffsetSeconds: Int): String {
        val t = wallClock(epochSeconds, utcOffsetSeconds)
        return "${p2(t.hour)}:${p2(t.minute)}:${p2(t.second)}"
    }

    /**
     * ISO 8601 date-time as UDDF writes it: the local time, followed by the UTC offset
     * when the dive has one ("2024-05-30T04:14:00+02:00"). With no offset known the zone
     * is left out, which ISO 8601 reads as local time.
     */
    fun isoDateTime(epochSeconds: Long, utcOffsetSeconds: Int): String {
        val local = "${date(epochSeconds, utcOffsetSeconds)}T${time(epochSeconds, utcOffsetSeconds)}"
        if (utcOffsetSeconds == 0) return local
        val abs = kotlin.math.abs(utcOffsetSeconds)
        return local + (if (utcOffsetSeconds < 0) "-" else "+") + "${p2(abs / 3600)}:${p2(abs % 3600 / 60)}"
    }

    /** "YYYY-MM-DD HH:MM:SS" local wall clock, space-separated (used by MacDive). */
    fun spaceDateTime(epochSeconds: Long, utcOffsetSeconds: Int): String =
        "${date(epochSeconds, utcOffsetSeconds)} ${time(epochSeconds, utcOffsetSeconds)}"

    /** Parse "YYYY-MM-DD HH:MM:SS" into epoch seconds (offset treated as 0). */
    fun epochFromSpaceDateTime(value: String): Long {
        val parts = value.trim().split(" ")
        val datePart = parts.getOrNull(0) ?: return 0L
        val timePart = parts.getOrNull(1) ?: "00:00:00"
        return epochFromDateTime(datePart, timePart)
    }

    /** Parse "YYYY-MM-DD" + "HH:MM:SS" into epoch seconds (offset treated as 0). */
    fun epochFromDateTime(date: String, time: String): Long {
        val (y, mo, d) = date.split("-").map { it.toInt() }
        val timeParts = time.split(":")
        val h = timeParts.getOrNull(0)?.toInt() ?: 0
        val mi = timeParts.getOrNull(1)?.toInt() ?: 0
        val s = timeParts.getOrNull(2)?.toInt() ?: 0
        return LocalDateTime(y, mo, d, h, mi, s).toInstant(TimeZone.UTC).epochSeconds
    }

    /**
     * Parse an ISO 8601 date-time into epoch seconds and UTC offset. Extended
     * ("2008-10-25T16:05:00+02:00") and basic ("20081025T1605+0200") forms, a space for
     * the "T", and missing seconds or time are accepted. A "Z" or offset gives the real
     * instant and that offset; without one the time is local, kept with a zero offset.
     */
    fun fromIso(iso: String): Pair<Long, Int> {
        val m = ISO.matchEntire(iso.trim()) ?: throw FormatException("Not an ISO 8601 date-time: $iso")
        val g = m.groupValues
        fun num(i: Int) = g[i].ifEmpty { "0" }.toInt()
        val wall = LocalDateTime(num(1), num(2), num(3), num(4), num(5), num(6)).toInstant(TimeZone.UTC).epochSeconds
        val offset = when {
            g[7].isEmpty() || g[7] == "Z" -> 0
            else -> (if (g[8] == "-") -1 else 1) * (num(9) * 3600 + num(10) * 60)
        }
        return (wall - offset) to offset
    }

    private val ISO = Regex(
        """(\d{4})-?(\d{2})-?(\d{2})(?:[T ](\d{2}):?(\d{2})(?::?(\d{2})(?:[.,]\d+)?)?)?(Z|([+-])(\d{2}):?(\d{2})?)?""",
    )
}
