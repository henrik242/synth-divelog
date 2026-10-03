package no.synth.divelog.core.formats

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant

/**
 * Dive start times are stored as epoch seconds plus a UTC offset. The file
 * formats mostly carry local wall-clock times with no zone, so we render the
 * wall clock and, on read, keep the value with a zero offset.
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

    /** ISO-8601 local date-time, e.g. 2024-05-30T04:14:00 (used by UDDF). */
    fun isoDateTime(epochSeconds: Long, utcOffsetSeconds: Int): String =
        "${date(epochSeconds, utcOffsetSeconds)}T${time(epochSeconds, utcOffsetSeconds)}"

    /** Parse "YYYY-MM-DD" + "HH:MM:SS" into epoch seconds (offset treated as 0). */
    fun epochFromDateTime(date: String, time: String): Long {
        val (y, mo, d) = date.split("-").map { it.toInt() }
        val timeParts = time.split(":")
        val h = timeParts.getOrNull(0)?.toInt() ?: 0
        val mi = timeParts.getOrNull(1)?.toInt() ?: 0
        val s = timeParts.getOrNull(2)?.toInt() ?: 0
        return LocalDateTime(y, mo, d, h, mi, s).toInstant(TimeZone.UTC).epochSeconds
    }

    /** Parse an ISO-8601 local date-time (optionally with zone, which is ignored). */
    fun epochFromIso(iso: String): Long {
        val core = iso.trim()
        val tIndex = core.indexOf('T')
        if (tIndex < 0) return epochFromDateTime(core, "00:00:00")
        val datePart = core.substring(0, tIndex)
        var timePart = core.substring(tIndex + 1)
        // Drop any timezone suffix.
        val zoneChars = listOf('Z', '+')
        for (z in zoneChars) {
            val idx = timePart.indexOf(z)
            if (idx > 0) timePart = timePart.substring(0, idx)
        }
        // A '-' in the time part would only be a zone offset.
        val minusIdx = timePart.indexOf('-')
        if (minusIdx > 0) timePart = timePart.substring(0, minusIdx)
        return epochFromDateTime(datePart, timePart)
    }
}
