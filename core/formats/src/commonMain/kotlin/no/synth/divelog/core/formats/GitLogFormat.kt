package no.synth.divelog.core.formats

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Instant
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType as DiveEventType
import no.synth.divelog.core.model.Sample

/**
 * Reads and writes the git-tree dive-log storage used by the dive cloud. Unlike
 * the single-document formats, a logbook here is a set of files keyed by path:
 * dives under YYYY/MM/<day-time>/, one Dive and one or more Divecomputer blob per
 * dive, and sites under 01-Divesites/. Values carry their unit as a suffix, so
 * parsing reads the number and ignores the rest.
 *
 * This covers dives (metadata, cylinders, samples, events) and sites (name and
 * gps). Trips, pictures, weightsystems, filter presets and per-dive-computer
 * extra data are not translated; unknown files and keys are skipped on read.
 */
class GitLogFormat {

    // --- Writing ---

    fun write(log: DiveLog): Map<String, String> {
        val files = LinkedHashMap<String, String>()

        val sites = log.dives.mapNotNull { it.site }.distinctBy { fullSiteName(it) }
        for (site in sites) {
            files["$SITES_DIR/Site-${siteUuid(fullSiteName(site))}"] = siteFile(site)
        }

        for (dive in log.dives) {
            val wc = wallClock(dive.startEpochSeconds, dive.utcOffsetSeconds)
            val dir = "${p4(wc.year)}/${p2(wc.month.ordinal + 1)}/${diveDirName(wc)}"
            val diveName = if (dive.number != null) "Dive-${dive.number}" else "Dive"
            files["$dir/$diveName"] = diveFile(dive)

            val indexed = dive.computers.size > 1
            dive.computers.forEachIndexed { i, computer ->
                val name = if (indexed) "Divecomputer-${p3(i + 1)}" else "Divecomputer"
                files["$dir/$name"] = divecomputerFile(dive, computer)
            }
        }
        return files
    }

    private fun diveFile(dive: DiveEntry): String {
        val b = StringBuilder()
        if (dive.durationSeconds > 0) b.append("duration ").append(clock(dive.durationSeconds)).append(" min\n")
        dive.rating?.let { b.append("rating ").append(it).append('\n') }
        dive.visibility?.let { b.append("visibility ").append(it).append('\n') }
        dive.site?.let { b.append("divesiteid ").append(siteUuid(fullSiteName(it))).append('\n') }
        if (dive.buddies.isNotEmpty()) {
            b.append("buddy ").append(quote(dive.buddies.joinToString(", "))).append('\n')
        }
        dive.notes?.let { b.append("notes ").append(quote(it)).append('\n') }
        for (tank in dive.tanks) b.append(cylinderLine(tank)).append('\n')
        dive.airTempMk?.let { b.append("airtemp ").append(milli(it - ZERO_C_MK)).append("°C\n") }
        dive.waterTempMk?.let { b.append("watertemp ").append(milli(it - ZERO_C_MK)).append("°C\n") }
        return b.toString()
    }

    private fun cylinderLine(tank: TankEntry): String {
        val b = StringBuilder("cylinder")
        tank.volumeMl?.let { b.append(" vol=").append(milli(it)).append('l') }
        tank.workingPressureMbar?.let { b.append(" workpressure=").append(milli(it)).append("bar") }
        tank.o2Permille?.let { b.append(" o2=").append(FormatUnits.permilleToPercent(it)) }
        tank.hePermille?.takeIf { it > 0 }?.let { b.append(" he=").append(FormatUnits.permilleToPercent(it)) }
        tank.startPressureMbar?.let { b.append(" start=").append(milli(it)).append("bar") }
        tank.endPressureMbar?.let { b.append(" end=").append(milli(it)).append("bar") }
        return b.toString()
    }

    private fun divecomputerFile(dive: DiveEntry, computer: ComputerEntry): String {
        val b = StringBuilder()
        computer.model?.let { b.append("model ").append(quote(it)).append('\n') }
        (computer.maxDepthMm ?: dive.maxDepthMm)?.let { b.append("maxdepth ").append(milli(it)).append("m\n") }
        (computer.meanDepthMm ?: dive.meanDepthMm)?.let { b.append("meandepth ").append(milli(it)).append("m\n") }
        computer.waterTempMk?.let { b.append("watertemp ").append(milli(it - ZERO_C_MK)).append("°C\n") }
        computer.airTempMk?.let { b.append("airtemp ").append(milli(it - ZERO_C_MK)).append("°C\n") }
        for (ev in computer.events.sortedBy { it.timeOffsetSeconds }) b.append(eventLine(ev)).append('\n')
        for (s in computer.samples) b.append(sampleLine(s)).append('\n')
        return b.toString()
    }

    private fun eventLine(ev: Event): String {
        val b = StringBuilder("event ").append(clock(ev.timeOffsetSeconds))
        b.append(" name=").append(quote(eventName(ev.type)))
        if (ev.type == DiveEventType.GAS_SWITCH && ev.value != null) {
            val o2 = ((ev.value!! shr 8) and 0xFF).toInt()
            val he = (ev.value!! and 0xFF).toInt()
            b.append(" o2=").append(o2).append(".0%")
            if (he > 0) b.append(" he=").append(he).append(".0%")
        }
        return b.toString()
    }

    private fun sampleLine(s: Sample): String {
        val b = StringBuilder(clock(s.timeOffsetSeconds))
        s.depthMm?.let { b.append(' ').append(milli(it)).append('m') }
        s.temperatureMk?.let { b.append(' ').append(milli(it - ZERO_C_MK)).append("°C") }
        for ((idx, mbar) in s.tankPressuresMbar.entries.sortedBy { it.key }.map { it.key to it.value }) {
            b.append(' ').append(milli(mbar)).append("bar:").append(idx)
        }
        s.ndlSeconds?.let { b.append(" ndl=").append(clock(it)) }
        s.stopDepthMm?.let { b.append(" stopdepth=").append(milli(it)).append('m') }
        s.stopTimeSeconds?.let { b.append(" stoptime=").append(clock(it)) }
        s.cnsPermille?.let { b.append(" cns=").append(it / 10).append('%') }
        s.ppO2Mbar?.let { b.append(" po2=").append(milli(it)).append("bar") }
        return b.toString()
    }

    private fun siteFile(site: SiteRef): String {
        val b = StringBuilder("name ").append(quote(fullSiteName(site))).append('\n')
        if (site.latitude != null && site.longitude != null) {
            b.append("gps ").append(deg6(site.latitude)).append(' ').append(deg6(site.longitude)).append('\n')
        }
        return b.toString()
    }

    // --- Reading ---

    fun read(files: Map<String, String>): DiveLog {
        val sitesByUuid = HashMap<String, SiteRef>()
        for ((path, content) in files) {
            val name = path.substringAfterLast('/')
            if (path.contains("$SITES_DIR/") && name.startsWith("Site-")) {
                sitesByUuid[name.removePrefix("Site-")] = parseSite(content)
            }
        }

        val groups = LinkedHashMap<String, DiveGroup>()
        for ((path, content) in files) {
            val segments = path.split('/')
            val diveIdx = segments.indexOfFirst { DIVE_DIR.matches(it) }
            if (diveIdx < 0) continue
            val fileName = segments.last()
            if (segments.size <= diveIdx + 1) continue
            val year = segments.take(diveIdx).firstOrNull { YEAR.matches(it) } ?: continue
            val month = segments.take(diveIdx).firstOrNull { MONTH.matches(it) } ?: continue
            val dirPath = segments.take(diveIdx + 1).joinToString("/")
            val group = groups.getOrPut(dirPath) {
                DiveGroup(epochOf(year.toInt(), month.toInt(), segments[diveIdx]))
            }
            when {
                fileName.startsWith("Divecomputer") -> group.computers[fileName] = content
                fileName.startsWith("Dive") -> {
                    group.diveFile = content
                    val suffix = fileName.removePrefix("Dive")
                    group.number = if (suffix.startsWith("-")) suffix.drop(1).toIntOrNull() else null
                }
            }
        }

        val serials = serialsByDeviceId(files[SETTINGS_FILE].orEmpty())
        val dives = groups.values.map { buildDive(it, sitesByUuid, serials) }.sortedBy { it.startEpochSeconds }
        return DiveLog(dives)
    }

    /**
     * Serials from the settings file's `divecomputerid "<model>" deviceid=<hex> serial="<s>"`
     * lines, keyed by device id. A log without them has no serials.
     */
    private fun serialsByDeviceId(settings: String): Map<String, String> =
        settings.lines().filter { it.startsWith("divecomputerid ") }.mapNotNull { line ->
            val id = Regex("\\bdeviceid=\"?([0-9a-fA-F]+)").find(line)?.groupValues?.get(1)
            val serial = Regex("\\bserial=(\"(?:[^\"\\\\]|\\\\.)*\"|\\S+)").find(line)?.groupValues?.get(1)
                ?.let { unquote(it) }?.trim()
            if (id == null || serial.isNullOrEmpty()) null else id.lowercase() to serial
        }.toMap()

    private fun buildDive(group: DiveGroup, sites: Map<String, SiteRef>, serials: Map<String, String>): DiveEntry {
        val computers = group.computers.entries.sortedBy { it.key }.map { parseComputer(it.value, serials) }
        val primary = computers.firstOrNull()

        var duration = 0
        var rating: Int? = null
        var visibility: Int? = null
        var siteUuid: String? = null
        val buddies = mutableListOf<String>()
        var notes: String? = null
        var airTempMk: Int? = null
        var waterTempMk: Int? = null
        val tanks = mutableListOf<TankEntry>()

        for (line in recordLines(group.diveFile)) {
            val keyword = line.substringBefore(' ')
            val rest = line.substring(keyword.length).trim()
            when (keyword) {
                "duration" -> FormatUnits.clockToSeconds(rest)?.let { duration = it }
                "rating" -> rating = rest.toIntOrNull()
                "visibility" -> visibility = rest.toIntOrNull()
                "divesiteid" -> siteUuid = rest.trim()
                "buddy" -> unquote(rest).split(",").map { it.trim() }.filter { it.isNotEmpty() }.forEach { buddies.add(it) }
                "notes" -> notes = unquote(rest)
                "airtemp" -> airTempMk = tempMk(rest)
                "watertemp" -> waterTempMk = tempMk(rest)
                "cylinder" -> tanks.add(parseCylinder(rest, tanks.size))
            }
        }

        return DiveEntry(
            number = group.number,
            startEpochSeconds = group.startEpochSeconds,
            utcOffsetSeconds = 0,
            durationSeconds = duration,
            maxDepthMm = primary?.maxDepthMm,
            meanDepthMm = primary?.meanDepthMm,
            waterTempMk = waterTempMk ?: primary?.waterTempMk,
            airTempMk = airTempMk ?: primary?.airTempMk,
            notes = notes,
            rating = rating,
            visibility = visibility,
            site = siteUuid?.let { sites[it] },
            buddies = buddies,
            tanks = tanks,
            computers = computers,
        )
    }

    private fun parseComputer(content: String, serials: Map<String, String>): ComputerEntry {
        var model: String? = null
        var serial: String? = null
        var maxDepthMm: Int? = null
        var meanDepthMm: Int? = null
        var waterTempMk: Int? = null
        var airTempMk: Int? = null
        val samples = mutableListOf<Sample>()
        val events = mutableListOf<Event>()

        for (line in recordLines(content)) {
            val first = line.firstOrNull() ?: continue
            if (first !in 'a'..'z') {
                parseSample(line, samples.lastOrNull())?.let { samples.add(it) }
                continue
            }
            val keyword = line.substringBefore(' ')
            val rest = line.substring(keyword.length).trim()
            when (keyword) {
                "model" -> model = unquote(rest)
                "deviceid" -> serial = serials[rest.trim().lowercase()]
                "maxdepth" -> maxDepthMm = depthMm(rest)
                "meandepth" -> meanDepthMm = depthMm(rest)
                "watertemp" -> waterTempMk = tempMk(rest)
                "airtemp" -> airTempMk = tempMk(rest)
                "event" -> parseEvent(rest)?.let { events.add(it) }
            }
        }
        return ComputerEntry(model, maxDepthMm, meanDepthMm, waterTempMk, airTempMk, samples, events, serial)
    }

    private fun parseSample(line: String, previous: Sample?): Sample? {
        val tokens = line.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        val time = parseClock(tokens[0]) ?: return null

        var depthMm = previous?.depthMm
        var temperatureMk = previous?.temperatureMk
        var ndlSeconds = previous?.ndlSeconds
        var stopDepthMm = previous?.stopDepthMm
        var stopTimeSeconds = previous?.stopTimeSeconds
        var cnsPermille = previous?.cnsPermille
        var ppO2Mbar = previous?.ppO2Mbar
        val pressures = mutableMapOf<Int, Int>()

        for (tok in tokens.drop(1)) {
            if (tok.contains('=')) {
                val key = tok.substringBefore('=')
                val value = tok.substringAfter('=')
                when (key) {
                    "ndl" -> ndlSeconds = parseClock(value)
                    "stopdepth" -> stopDepthMm = depthMm(value)
                    "stoptime" -> stopTimeSeconds = parseClock(value)
                    "cns" -> value.trimEnd('%').toIntOrNull()?.let { cnsPermille = it * 10 }
                    "po2" -> ppO2Mbar = mbar(value)
                }
            } else {
                val num = FormatUnits.leadingNumber(tok) ?: continue
                val unit = tok.dropWhile { it.isDigit() || it == '.' || it == '-' || it == '+' }
                when (unit.firstOrNull()) {
                    'm' -> depthMm = (num * 1000).roundToInt()
                    'b' -> {
                        val sensor = unit.substringAfter(':', "0").toIntOrNull() ?: 0
                        pressures[sensor] = (num * 1000).roundToInt()
                    }
                    else -> temperatureMk = (num * 1000).roundToInt() + ZERO_C_MK
                }
            }
        }
        return Sample(
            timeOffsetSeconds = time,
            depthMm = depthMm,
            temperatureMk = temperatureMk,
            ppO2Mbar = ppO2Mbar,
            ndlSeconds = ndlSeconds,
            stopDepthMm = stopDepthMm,
            stopTimeSeconds = stopTimeSeconds,
            cnsPermille = cnsPermille,
            tankPressuresMbar = pressures,
        )
    }

    private fun parseEvent(rest: String): Event? {
        val time = parseClock(rest.substringBefore(' ')) ?: return null
        val name = Regex("name=\"((?:[^\"\\\\]|\\\\.)*)\"").find(rest)?.groupValues?.get(1)?.let { unescape(it) }
        val type = eventType(name)
        val value = if (type == DiveEventType.GAS_SWITCH) {
            val o2 = Regex("\\bo2=([0-9.]+)").find(rest)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt() ?: 0
            val he = Regex("\\bhe=([0-9.]+)").find(rest)?.groupValues?.get(1)?.toDoubleOrNull()?.toInt() ?: 0
            ((o2 shl 8) or he).toLong()
        } else {
            null
        }
        return Event(timeOffsetSeconds = time, type = type, value = value)
    }

    private fun parseCylinder(rest: String, index: Int): TankEntry {
        val kv = HashMap<String, String>()
        for (tok in rest.split(Regex("\\s+"))) {
            if (tok.contains('=')) kv[tok.substringBefore('=')] = tok.substringAfter('=')
        }
        return TankEntry(
            index = index,
            volumeMl = kv["vol"]?.let { mbar(it) },
            workingPressureMbar = kv["workpressure"]?.let { mbar(it) },
            startPressureMbar = kv["start"]?.let { mbar(it) },
            endPressureMbar = kv["end"]?.let { mbar(it) },
            o2Permille = kv["o2"]?.let { FormatUnits.percentToPermille(it) },
            hePermille = kv["he"]?.let { FormatUnits.percentToPermille(it) },
        )
    }

    private fun parseSite(content: String): SiteRef {
        var name = ""
        var gps: String? = null
        for (line in recordLines(content)) {
            val keyword = line.substringBefore(' ')
            val rest = line.substring(keyword.length).trim()
            when (keyword) {
                "name" -> name = unquote(rest)
                "gps" -> gps = rest
            }
        }
        val coords = gps?.trim()?.split(Regex("[\\s,]+"))?.filter { it.isNotEmpty() }
        val lat = coords?.getOrNull(0)?.toDoubleOrNull()
        val lon = coords?.getOrNull(1)?.toDoubleOrNull()
        val parts = name.split(" / ")
        return when (parts.size) {
            3 -> SiteRef(parts[2], parts[0], parts[1], lat, lon)
            2 -> SiteRef(parts[1], parts[0], null, lat, lon)
            else -> SiteRef(name, null, null, lat, lon)
        }
    }

    // --- Helpers ---

    private class DiveGroup(val startEpochSeconds: Long) {
        var number: Int? = null
        var diveFile: String = ""
        val computers = LinkedHashMap<String, String>()
    }

    private fun wallClock(epochSeconds: Long, utcOffsetSeconds: Int) =
        Instant.fromEpochSeconds(epochSeconds + utcOffsetSeconds).toLocalDateTime(TimeZone.UTC)

    private fun diveDirName(wc: kotlinx.datetime.LocalDateTime): String {
        val weekday = WEEKDAYS[LocalDate(wc.year, wc.month.ordinal + 1, wc.day).dayOfWeek.ordinal]
        return "${p2(wc.day)}-$weekday-${p2(wc.hour)}=${p2(wc.minute)}=${p2(wc.second)}"
    }

    private fun epochOf(year: Int, month: Int, diveDir: String): Long {
        val m = requireNotNull(DIVE_DIR.find(diveDir)) { "not a dive directory: $diveDir" }
        val day = m.groupValues[1].toInt()
        val h = m.groupValues[2].toInt()
        val mi = m.groupValues[3].toInt()
        val s = m.groupValues[4].toInt()
        return FormatDateTime.epochFromDateTime("${p4(year)}-${p2(month)}-${p2(day)}", "${p2(h)}:${p2(mi)}:${p2(s)}")
    }

    /** Value/1000 with one to three decimals, trailing zeros trimmed. */
    private fun milli(value: Int): String {
        val sign = if (value < 0) "-" else ""
        val a = abs(value)
        val frac = (a % 1000).toString().padStart(3, '0').trimEnd('0').ifEmpty { "0" }
        return "$sign${a / 1000}.$frac"
    }

    private fun deg6(value: Double): String {
        val sign = if (value < 0) "-" else ""
        val udeg = (abs(value) * 1_000_000).roundToLong()
        return "$sign${udeg / 1_000_000}.${(udeg % 1_000_000).toString().padStart(6, '0')}"
    }

    private fun clock(seconds: Int): String = "${seconds / 60}:${p2(seconds % 60)}"

    private fun parseClock(text: String): Int? {
        val parts = text.trim().split(":")
        return when (parts.size) {
            2 -> {
                val m = parts[0].toIntOrNull() ?: return null
                val s = parts[1].toIntOrNull() ?: return null
                m * 60 + s
            }
            1 -> parts[0].toIntOrNull()
            else -> null
        }
    }

    private fun depthMm(text: String): Int? = FormatUnits.leadingNumber(text)?.let { (it * 1000).roundToInt() }
    private fun mbar(text: String): Int? = FormatUnits.leadingNumber(text)?.let { (it * 1000).roundToInt() }
    private fun tempMk(text: String): Int? = FormatUnits.leadingNumber(text)?.let { (it * 1000).roundToInt() + ZERO_C_MK }

    private fun fullSiteName(site: SiteRef): String =
        listOfNotNull(site.country, site.place, site.name).joinToString(" / ")

    private fun siteUuid(name: String): String {
        var h = 2166136261u
        for (c in name) { h = h xor c.code.toUInt(); h *= 16777619u }
        return h.toString(16).padStart(8, '0').take(8)
    }

    /** Escape and wrap a string the way the git format expects. */
    private fun quote(value: String): String {
        val escaped = value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\n\t")
        return "\"$escaped\""
    }

    private fun unquote(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("\"")) return trimmed
        return unescape(trimmed.drop(1).removeSuffix("\""))
    }

    private fun unescape(body: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < body.length) {
            val c = body[i]
            when {
                c == '\\' && i + 1 < body.length -> { sb.append(body[i + 1]); i += 2 }
                c == '\n' && i + 1 < body.length && body[i + 1] == '\t' -> { sb.append('\n'); i += 2 }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString()
    }

    /**
     * Split a file into logical lines, keeping a quoted value that spans newlines
     * on one line. A backslash escapes the next character.
     */
    private fun recordLines(content: String): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var inQuote = false
        var i = 0
        while (i < content.length) {
            val c = content[i]
            when {
                c == '\\' && inQuote && i + 1 < content.length -> { current.append(c).append(content[i + 1]); i += 2; continue }
                c == '"' -> { inQuote = !inQuote; current.append(c) }
                c == '\n' && !inQuote -> { if (current.isNotBlank()) lines.add(current.toString()); current.clear() }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotBlank()) lines.add(current.toString())
        return lines
    }

    private fun eventName(type: DiveEventType): String = when (type) {
        DiveEventType.GAS_SWITCH -> "gaschange"
        DiveEventType.BOOKMARK -> "bookmark"
        DiveEventType.WARNING -> "warning"
        DiveEventType.DECO -> "deco"
        DiveEventType.SURFACE -> "surface"
        DiveEventType.ASCENT_RATE -> "ascent"
        DiveEventType.OTHER -> "event"
    }

    private fun eventType(name: String?): DiveEventType = when (name) {
        "gaschange" -> DiveEventType.GAS_SWITCH
        "bookmark" -> DiveEventType.BOOKMARK
        "warning" -> DiveEventType.WARNING
        "deco" -> DiveEventType.DECO
        "surface" -> DiveEventType.SURFACE
        "ascent" -> DiveEventType.ASCENT_RATE
        else -> DiveEventType.OTHER
    }

    private fun p2(v: Int) = v.toString().padStart(2, '0')
    private fun p3(v: Int) = v.toString().padStart(3, '0')
    private fun p4(v: Int) = v.toString().padStart(4, '0')

    private companion object {
        const val SITES_DIR = "01-Divesites"
        const val SETTINGS_FILE = "00-Subsurface"
        const val ZERO_C_MK = 273_150
        val WEEKDAYS = arrayOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        // "07-Sun-13=08=10", with "~<hash>" appended when two dives share a start time.
        // Early builds of this app wrote "07-Sun=13=08=10"; accept that too.
        val DIVE_DIR = Regex("^(\\d{2})-[A-Za-z]{3}[-=](\\d{2})=(\\d{2})=(\\d{2})(~[0-9a-f]+)?$")
        val YEAR = Regex("^\\d{4}$")
        val MONTH = Regex("^\\d{2}$")
    }
}
