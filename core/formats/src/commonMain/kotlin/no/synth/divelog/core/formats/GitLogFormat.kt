package no.synth.divelog.core.formats

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant
import no.synth.divelog.core.formats.SubsurfaceShared.clock
import no.synth.divelog.core.formats.SubsurfaceShared.milli
import no.synth.divelog.core.model.Event
import no.synth.divelog.core.model.EventType as DiveEventType
import no.synth.divelog.core.model.GasSwitch
import no.synth.divelog.core.model.Sample

/**
 * Reads and writes the git-tree dive-log storage used by the dive cloud. Unlike
 * the single-document formats, a logbook here is a set of files keyed by path:
 * dives under YYYY/MM/<day-time>/, one Dive and one or more Divecomputer blob per
 * dive, sites under 01-Divesites/ and settings in 00-Subsurface. Values carry their
 * unit as a suffix, so parsing reads the number and ignores the rest. Sample values
 * other than tank pressures carry forward to the next sample.
 *
 * This covers dives (metadata, tags, cylinders, samples, events), sites (name, gps and
 * country/place taxonomy) and computer serials. Trips, pictures, weightsystems, filter
 * presets and other per-dive-computer extra data are not translated; unknown files and
 * keys are skipped on read.
 */
class GitLogFormat {

    // --- Writing ---

    fun write(log: DiveLog): Map<String, String> {
        val files = LinkedHashMap<String, String>()
        files[SETTINGS_FILE] = settingsFile(log)

        for (site in log.dives.mapNotNull { it.site }.distinct()) {
            files["$SITES_DIR/Site-${SubsurfaceShared.siteUuid(site)}"] = siteFile(site)
        }

        for (dive in log.dives) {
            val wc = wallClock(dive.startEpochSeconds, dive.utcOffsetSeconds)
            val base = "${p4(wc.year)}/${p2(wc.month.ordinal + 1)}/${diveDirName(wc)}"
            val diveName = if (dive.number != null) "Dive-${dive.number}" else "Dive"
            val content = LinkedHashMap<String, String>()
            content[diveName] = diveFile(dive)
            val tanks = SubsurfaceShared.tanksToWrite(dive)
            val computers = computersToWrite(dive)
            val indexed = computers.size > 1
            computers.forEachIndexed { i, computer ->
                val name = if (indexed) "Divecomputer-${p3(i + 1)}" else "Divecomputer"
                content[name] = divecomputerFile(dive, computer, tanks, if (i == 0) dive.utcOffsetSeconds else 0)
            }
            // Two dives starting the same second get a hash suffix, as the cloud repo does.
            var dir = base
            if (files.keys.any { it.startsWith("$dir/") }) {
                var seed = content.values.joinToString("\u0000")
                do {
                    dir = "$base~${hash7(seed)}"
                    seed += "\u0000"
                } while (files.keys.any { it.startsWith("$dir/") })
            }
            content.forEach { (name, text) -> files["$dir/$name"] = text }
        }
        return files
    }

    private fun settingsFile(log: DiveLog): String {
        val b = StringBuilder("version 3\n")
        log.dives.flatMap { it.computers }.filter { it.serial != null }.distinctBy { it.model to it.serial }.forEach { c ->
            val serial = c.serial ?: return@forEach
            b.append("divecomputerid ").append(quote(c.model.orEmpty()))
                .append(" deviceid=").append(SubsurfaceShared.deviceId(serial))
                .append(" serial=").append(quote(serial)).append('\n')
        }
        return b.toString()
    }

    /** The dive's computers; a dive without one gets a bare computer carrying its summary. */
    private fun computersToWrite(dive: DiveEntry): List<ComputerEntry> {
        val computers = dive.computers.ifEmpty {
            val summary = listOf(dive.maxDepthMm, dive.meanDepthMm, dive.waterTempMk, dive.airTempMk)
            if (summary.any { it != null } || dive.utcOffsetSeconds != 0) listOf(ComputerEntry()) else emptyList()
        }
        // The dive's summary is its first computer's.
        return computers.mapIndexed { i, c ->
            if (i > 0) c else c.copy(
                maxDepthMm = c.maxDepthMm ?: dive.maxDepthMm,
                meanDepthMm = c.meanDepthMm ?: dive.meanDepthMm,
                waterTempMk = c.waterTempMk ?: dive.waterTempMk,
                airTempMk = c.airTempMk ?: dive.airTempMk,
            )
        }
    }

    private fun diveFile(dive: DiveEntry): String {
        val b = StringBuilder()
        if (dive.durationSeconds > 0) b.append("duration ").append(clock(dive.durationSeconds)).append(" min\n")
        dive.rating?.let { b.append("rating ").append(it).append('\n') }
        dive.visibility?.let { b.append("visibility ").append(it).append('\n') }
        if (dive.tags.isNotEmpty()) b.append("tags ").append(dive.tags.joinToString(", ") { quote(it) }).append('\n')
        dive.site?.let { b.append("divesiteid ").append(SubsurfaceShared.siteUuid(it)).append('\n') }
        if (dive.buddies.isNotEmpty()) {
            b.append("buddy ").append(quote(dive.buddies.joinToString(", "))).append('\n')
        }
        dive.notes?.takeIf { it.isNotBlank() }?.let { b.append("notes ").append(quote(it)).append('\n') }
        for (tank in SubsurfaceShared.tanksToWrite(dive)) b.append(cylinderLine(tank)).append('\n')
        // Dive temperatures only where they differ from what the computers give.
        val computers = computersToWrite(dive)
        dive.airTempMk?.takeIf { it != SubsurfaceShared.meanTemp(computers.map { c -> c.airTempMk }) }
            ?.let { b.append("airtemp ").append(milli(it - ZERO_C_MK)).append("°C\n") }
        dive.waterTempMk?.takeIf { it != SubsurfaceShared.meanTemp(computers.map { c -> c.waterTempMk }) }
            ?.let { b.append("watertemp ").append(milli(it - ZERO_C_MK)).append("°C\n") }
        return b.toString()
    }

    private fun cylinderLine(tank: TankEntry): String {
        val b = StringBuilder("cylinder")
        tank.volumeMl?.let { b.append(" vol=").append(milli(it)).append('l') }
        tank.workingPressureMbar?.let { b.append(" workpressure=").append(milli(it)).append("bar") }
        tank.o2Permille?.let { o2 ->
            b.append(" o2=").append(SubsurfaceShared.percent(o2))
            tank.hePermille?.takeIf { it > 0 }?.let { b.append(" he=").append(SubsurfaceShared.percent(it)) }
        }
        tank.startPressureMbar?.let { b.append(" start=").append(milli(it)).append("bar") }
        tank.endPressureMbar?.let { b.append(" end=").append(milli(it)).append("bar") }
        return b.toString()
    }

    private fun divecomputerFile(dive: DiveEntry, computer: ComputerEntry, tanks: List<TankEntry>, utcOffset: Int): String {
        val b = StringBuilder()
        computer.model?.let { b.append("model ").append(quote(it)).append('\n') }
        computer.serial?.let { b.append("deviceid ").append(SubsurfaceShared.deviceId(it)).append('\n') }
        computer.startEpochSeconds?.takeIf { it != dive.startEpochSeconds }?.let { start ->
            b.append("date ").append(FormatDateTime.date(start, dive.utcOffsetSeconds)).append('\n')
            b.append("time ").append(FormatDateTime.time(start, dive.utcOffsetSeconds)).append('\n')
        }
        computer.durationSeconds?.takeIf { it != dive.durationSeconds && it > 0 }?.let {
            b.append("duration ").append(clock(it)).append("min\n")
        }
        computer.maxDepthMm?.let { b.append("maxdepth ").append(milli(it)).append("m\n") }
        computer.meanDepthMm?.let { b.append("meandepth ").append(milli(it)).append("m\n") }
        computer.airTempMk?.let { b.append("airtemp ").append(milli(it - ZERO_C_MK)).append("°C\n") }
        computer.waterTempMk?.let { b.append("watertemp ").append(milli(it - ZERO_C_MK)).append("°C\n") }
        computer.serial?.let { b.append("keyvalue ").append(quote(SubsurfaceShared.KEY_SERIAL)).append(' ').append(quote(it)).append('\n') }
        if (utcOffset != 0) {
            b.append("keyvalue ").append(quote(SubsurfaceShared.KEY_UTC_OFFSET)).append(' ')
                .append(quote(SubsurfaceShared.offsetText(utcOffset))).append('\n')
        }
        for (ev in computer.events.sortedBy { it.timeOffsetSeconds }) b.append(eventLine(ev, tanks)).append('\n')
        var last = Sample(timeOffsetSeconds = 0)
        for (s in computer.samples) {
            b.append(sampleLine(s, last)).append('\n')
            last = SubsurfaceShared.carried(last, s)
        }
        return b.toString()
    }

    private fun eventLine(ev: Event, tanks: List<TankEntry>): String {
        val b = StringBuilder("event ").append(clock(ev.timeOffsetSeconds))
        SubsurfaceShared.eventTypeNumber(ev)?.let { b.append(" type=").append(it) }
        SubsurfaceShared.eventValue(ev)?.let { b.append(" value=").append(it) }
        b.append(" name=").append(quote(SubsurfaceShared.eventName(ev.type)))
        val value = ev.value
        if (ev.type == DiveEventType.GAS_SWITCH && value != null) {
            val o2 = GasSwitch.o2Percent(value)
            val he = GasSwitch.hePercent(value)
            SubsurfaceShared.cylinderOf(tanks, o2, he)?.let { b.append(" cylinder=").append(it) }
            b.append(" o2=").append(o2).append(".0%")
            if (he > 0) b.append(" he=").append(he).append(".0%")
        }
        return b.toString()
    }

    /** One sample line; values other than depth and pressures only when they change. */
    private fun sampleLine(s: Sample, last: Sample): String {
        val b = StringBuilder(clock(s.timeOffsetSeconds).padStart(6))
        s.depthMm?.let { b.append(' ').append(milli(it)).append('m') }
        s.temperatureMk?.takeIf { it != last.temperatureMk }?.let { b.append(' ').append(milli(it - ZERO_C_MK)).append("°C") }
        for ((idx, mbar) in s.tankPressuresMbar.entries.sortedBy { it.key }) {
            b.append(' ').append(milli(mbar)).append("bar:").append(idx)
        }
        s.ndlSeconds?.takeIf { it != last.ndlSeconds }?.let { b.append(" ndl=").append(clock(it)) }
        s.stopTimeSeconds?.takeIf { it != last.stopTimeSeconds }?.let { b.append(" stoptime=").append(clock(it)) }
        s.stopDepthMm?.takeIf { it != last.stopDepthMm }?.let { b.append(" stopdepth=").append(milli(it)).append('m') }
        s.cnsPermille?.takeIf { it != last.cnsPermille }?.let { b.append(" cns=").append(it / 10).append('%') }
        s.ppO2Mbar?.takeIf { it != last.ppO2Mbar }?.let { b.append(" dc_supplied_ppo2=").append(milli(it)).append("bar") }
        return b.toString()
    }

    private fun siteFile(site: SiteRef): String {
        val b = StringBuilder("name ").append(quote(site.name)).append('\n')
        if (site.latitude != null && site.longitude != null) {
            b.append("gps ").append(SubsurfaceShared.deg6(site.latitude)).append(' ').append(SubsurfaceShared.deg6(site.longitude)).append('\n')
        }
        for ((cat, value) in SubsurfaceShared.geoOf(site)) {
            b.append("geo cat ").append(cat).append(" origin ").append(SubsurfaceShared.GEO_MANUAL).append(' ').append(quote(value)).append('\n')
        }
        return b.toString()
    }

    // --- Reading ---

    fun read(files: Map<String, String>): DiveLog {
        val sitesByUuid = HashMap<String, SiteRef>()
        for ((path, content) in files) {
            val name = path.substringAfterLast('/')
            if (path.contains("$SITES_DIR/") && name.startsWith("Site-")) {
                sitesByUuid[name.removePrefix("Site-").lowercase()] = parseSite(content)
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
        var duration = 0
        var rating: Int? = null
        var visibility: Int? = null
        var siteUuid: String? = null
        val buddies = mutableListOf<String>()
        val tags = mutableListOf<String>()
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
                "tags" -> quotedStrings(rest).filter { it.isNotBlank() }.forEach { tags.add(it) }
                "divesiteid" -> siteUuid = rest.trim().lowercase()
                "buddy" -> buddies.addAll(SubsurfaceShared.splitList(unquote(rest)))
                "notes" -> notes = unquote(rest).ifBlank { null }
                "airtemp" -> airTempMk = tempMk(rest)
                "watertemp" -> waterTempMk = tempMk(rest)
                "cylinder" -> tanks.add(parseCylinder(rest, tanks.size))
            }
        }

        val parsed = group.computers.entries.sortedBy { it.key }.map { parseComputer(it.value, tanks) }
        val offset = parsed.firstOrNull()?.extra?.get(SubsurfaceShared.KEY_UTC_OFFSET)?.trim()?.removePrefix("+")?.toIntOrNull() ?: 0
        val computers = parsed.map { it.build(serials, offset) }
        val primary = computers.firstOrNull()

        return DiveEntry(
            number = group.number,
            startEpochSeconds = group.wallEpochSeconds - offset,
            utcOffsetSeconds = offset,
            durationSeconds = duration,
            maxDepthMm = primary?.maxDepthMm,
            meanDepthMm = primary?.meanDepthMm,
            waterTempMk = waterTempMk ?: SubsurfaceShared.meanTemp(computers.map { it.waterTempMk }),
            airTempMk = airTempMk ?: SubsurfaceShared.meanTemp(computers.map { it.airTempMk }),
            notes = notes,
            rating = rating,
            visibility = visibility,
            site = siteUuid?.let { sites[it] },
            buddies = buddies,
            tags = tags,
            tanks = tanks,
            gasMixes = SubsurfaceShared.gasesOf(tanks),
            computers = computers.filterNot(SubsurfaceShared::isBare),
        )
    }

    private class ParsedComputer(
        val model: String?,
        val deviceId: String?,
        val date: String?,
        val time: String?,
        val durationSeconds: Int?,
        val maxDepthMm: Int?,
        val meanDepthMm: Int?,
        val waterTempMk: Int?,
        val airTempMk: Int?,
        val extra: Map<String, String>,
        val sensorTanks: Map<Int, Int>,
        val samples: List<Sample>,
        val events: List<Event>,
    ) {
        fun build(serials: Map<String, String>, utcOffset: Int) = ComputerEntry(
            model = model?.takeIf { it.isNotBlank() },
            maxDepthMm = maxDepthMm,
            meanDepthMm = meanDepthMm,
            waterTempMk = waterTempMk,
            airTempMk = airTempMk,
            samples = SubsurfaceShared.mapSensors(samples, sensorTanks),
            events = events,
            serial = extra[SubsurfaceShared.KEY_SERIAL] ?: deviceId?.let { serials[it] },
            startEpochSeconds = date?.let { FormatDateTime.epochFromDateTime(it, time ?: "00:00:00") - utcOffset },
            durationSeconds = durationSeconds,
        )
    }

    private fun parseComputer(content: String, tanks: List<TankEntry>): ParsedComputer {
        var model: String? = null
        var deviceId: String? = null
        var date: String? = null
        var time: String? = null
        var duration: Int? = null
        var maxDepthMm: Int? = null
        var meanDepthMm: Int? = null
        var waterTempMk: Int? = null
        var airTempMk: Int? = null
        val extra = LinkedHashMap<String, String>()
        val sensorTanks = HashMap<Int, Int>()
        val samples = mutableListOf<Sample>()
        val events = mutableListOf<Event>()

        for (line in recordLines(content)) {
            val first = line.trimStart().firstOrNull() ?: continue
            if (first !in 'a'..'z') {
                parseSample(line, samples.lastOrNull())?.let { samples.add(it) }
                continue
            }
            val keyword = line.substringBefore(' ')
            val rest = line.substring(keyword.length).trim()
            when (keyword) {
                "model" -> model = unquote(rest)
                "deviceid" -> deviceId = rest.trim().lowercase()
                "date" -> date = rest.trim()
                "time" -> time = rest.trim()
                "duration" -> duration = FormatUnits.clockToSeconds(rest)
                "maxdepth" -> maxDepthMm = depthMm(rest)
                "meandepth" -> meanDepthMm = depthMm(rest)
                "watertemp" -> waterTempMk = tempMk(rest)
                "airtemp" -> airTempMk = tempMk(rest)
                "keyvalue" -> quotedStrings(rest).let { kv ->
                    val key = kv.getOrNull(0)
                    val value = kv.getOrNull(1)?.trim()
                    if (key != null && !value.isNullOrEmpty()) extra[key] = value
                }
                "tanksensormapping" -> quotedStrings(rest).mapNotNull { it.trim().toIntOrNull() }.let { ids ->
                    if (ids.size == 2) sensorTanks[ids[0]] = ids[1]
                }
                "event" -> parseEvent(rest, tanks)?.let { events.add(it) }
            }
        }
        return ParsedComputer(model, deviceId, date, time, duration, maxDepthMm, meanDepthMm, waterTempMk, airTempMk, extra, sensorTanks, samples, events)
    }

    /** A sample; values it leaves out carry over from [previous], except tank pressures. */
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
        var dcPpO2 = false
        val pressures = mutableMapOf<Int, Int>()

        for (tok in tokens.drop(1)) {
            if (tok.contains('=')) {
                val key = tok.substringBefore('=')
                val value = tok.substringAfter('=')
                when (key) {
                    "ndl" -> ndlSeconds = parseClock(value)
                    "stopdepth" -> stopDepthMm = depthMm(value)
                    "stoptime" -> stopTimeSeconds = parseClock(value)
                    "cns" -> FormatUnits.percentToPermille(value)?.let { cnsPermille = it }
                    "dc_supplied_ppo2" -> { ppO2Mbar = mbar(value); dcPpO2 = true }
                    // Earlier exports of this app wrote the computer's ppO2 as po2.
                    "po2" -> if (!dcPpO2) ppO2Mbar = mbar(value)
                }
            } else {
                val num = FormatUnits.leadingNumber(tok) ?: continue
                val unit = tok.dropWhile { it.isDigit() || it == '.' || it == '-' || it == '+' }
                when (unit.firstOrNull()) {
                    'm' -> depthMm = (num * 1000).roundToInt()
                    'b' -> {
                        val sensor = unit.substringAfter(':', "0").toIntOrNull() ?: 0
                        (num * 1000).roundToInt().takeIf { it > 0 }?.let { pressures[sensor] = it }
                    }
                    '°', 'C' -> temperatureMk = (num * 1000).roundToInt() + ZERO_C_MK
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

    private fun parseEvent(rest: String, tanks: List<TankEntry>): Event? {
        val time = parseClock(rest.substringBefore(' ')) ?: return null
        val name = Regex("name=\"((?:[^\"\\\\]|\\\\.)*)\"").find(rest)?.groupValues?.get(1)?.let { unescape(it) }
        val type = SubsurfaceShared.eventType(name)
        fun key(k: String) = Regex("\\b$k=([0-9.+-]+)").find(rest)?.groupValues?.get(1)
        val value = if (type == DiveEventType.GAS_SWITCH) {
            SubsurfaceShared.gasSwitchValue(key("o2"), key("he"), key("cylinder"), key("value"), tanks)
        } else {
            key("value")?.toLongOrNull()
        }
        return Event(timeOffsetSeconds = time, type = type, value = value)
    }

    /** A cylinder. One written without a mix is left without a gas rather than assumed to be air. */
    private fun parseCylinder(rest: String, index: Int): TankEntry {
        val kv = HashMap<String, String>()
        for (tok in rest.split(Regex("\\s+"))) {
            if (tok.contains('=')) kv[tok.substringBefore('=')] = tok.substringAfter('=')
        }
        val o2 = kv["o2"]?.let { FormatUnits.percentToPermille(it) }
        return TankEntry(
            index = index,
            volumeMl = kv["vol"]?.let { mbar(it) },
            workingPressureMbar = kv["workpressure"]?.let { mbar(it) },
            startPressureMbar = kv["start"]?.let { mbar(it) },
            endPressureMbar = kv["end"]?.let { mbar(it) },
            o2Permille = o2,
            hePermille = kv["he"]?.let { FormatUnits.percentToPermille(it) } ?: o2?.let { 0 },
        )
    }

    private fun parseSite(content: String): SiteRef {
        var name = ""
        var gps: String? = null
        val geo = LinkedHashMap<Int, String>()
        for (line in recordLines(content)) {
            val keyword = line.substringBefore(' ')
            val rest = line.substring(keyword.length).trim()
            when (keyword) {
                "name" -> name = unquote(rest)
                "gps" -> gps = rest
                "geo" -> {
                    val cat = Regex("\\bcat (\\d+)").find(rest)?.groupValues?.get(1)?.toIntOrNull()
                    val value = quotedStrings(rest).firstOrNull()?.trim()
                    if (cat != null && !value.isNullOrEmpty()) geo.getOrPut(cat) { value }
                }
            }
        }
        val coords = gps?.trim()?.split(Regex("[\\s,]+"))?.filter { it.isNotEmpty() }
        return SubsurfaceShared.site(name, coords?.getOrNull(0)?.toDoubleOrNull(), coords?.getOrNull(1)?.toDoubleOrNull(), geo)
    }

    // --- Helpers ---

    private class DiveGroup(val wallEpochSeconds: Long) {
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

    /** Seven hex digits of a stable hash of [text]. */
    private fun hash7(text: String): String {
        var h = 2166136261u
        for (c in text) { h = h xor c.code.toUInt(); h *= 16777619u }
        return h.toString(16).padStart(8, '0').take(7)
    }

    private fun parseClock(text: String): Int? {
        val parts = text.trim().split(":")
        return when (parts.size) {
            2 -> {
                val m = parts[0].trim().toIntOrNull() ?: return null
                val s = parts[1].trim().toIntOrNull() ?: return null
                m * 60 + s
            }
            1 -> parts[0].trim().toIntOrNull()
            else -> null
        }
    }

    private fun depthMm(text: String): Int? = FormatUnits.leadingNumber(text)?.let { (it * 1000).roundToInt() }
    private fun mbar(text: String): Int? = FormatUnits.leadingNumber(text)?.let { (it * 1000).roundToInt() }
    private fun tempMk(text: String): Int? = FormatUnits.leadingNumber(text)?.let { (it * 1000).roundToInt() + ZERO_C_MK }

    /**
     * Escape and wrap a string the way the git format expects: backslash and quote are
     * escaped, and a line break inside the value is followed by a tab unless the next
     * character is another line break.
     */
    private fun quote(value: String): String {
        val b = StringBuilder("\"")
        for ((i, c) in value.withIndex()) {
            when (c) {
                '\\' -> b.append("\\\\")
                '"' -> b.append("\\\"")
                '\n' -> b.append(if (value.getOrNull(i + 1) == '\n') "\n" else "\n\t")
                else -> b.append(c)
            }
        }
        return b.append('"').toString()
    }

    private fun unquote(text: String): String {
        val trimmed = text.trim()
        if (!trimmed.startsWith("\"")) return trimmed
        return unescape(trimmed.drop(1).removeSuffix("\""))
    }

    /** Every quoted string in [text], unescaped: `"a", "b"` gives a and b. */
    private fun quotedStrings(text: String): List<String> =
        Regex("\"((?:[^\"\\\\]|\\\\.)*)\"").findAll(text).map { unescape(it.groupValues[1]) }.toList()

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
