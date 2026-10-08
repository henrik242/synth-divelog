package no.synth.divelog.core.logbook.download

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.synth.divelog.core.db.ImportDecision
import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerKind
import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DiveLogParser
import no.synth.divelog.core.divecomputer.DownloadCancelledException
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.transport.RecordingTransport
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.logbook.AppContainer
import no.synth.divelog.core.logbook.format.Format
import no.synth.divelog.core.model.ComputerNames
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.units.UnitSystem

/**
 * A dive computer the download picker offers: a [DiveComputerKind] (line settings,
 * protocol, parser) plus how the logbook recognises the physical computer.
 *
 * [identityKey] is a stable dedupe key standing in for the device identity, stored as
 * the device's address (dive dedupe keys on device plus fingerprint). A Suunto on the
 * cable is told apart by the serial it reports. A Shearwater reports no serial, so with
 * [byAddress] its key carries the Bluetooth address it was reached on.
 */
enum class DiveComputerType(
    val kind: DiveComputerKind,
    private val identityKey: String,
    private val byAddress: Boolean = false,
) {
    SUUNTO_VYPER(DiveComputerKind.SUUNTO_VYPER, "usb-serial:suunto-vyper"),
    SUUNTO_VYPER2(DiveComputerKind.SUUNTO_VYPER2, "usb-serial:suunto-vyper2"),
    SHEARWATER_PETREL(DiveComputerKind.SHEARWATER_PETREL, "serial-spp:shearwater-petrel", byAddress = true),
    SHEARWATER_PREDATOR(DiveComputerKind.SHEARWATER_PREDATOR, "serial-spp:shearwater-predator", byAddress = true),
    ;

    val displayName: String get() = kind.displayName

    val serialParams: SerialParams get() = kind.serialParams

    fun protocol(transport: Transport): DiveComputerProtocol = kind.protocol(transport)

    fun parser(): DiveLogParser = kind.parser()

    /** Whether [device] is one this type reads, by its stored identity key. */
    internal fun owns(device: Device): Boolean {
        val key = device.bluetoothAddress ?: return false
        return key == identityKey || key.startsWith("$identityKey:") || key.startsWith("$identityKey@")
    }

    /** Identity for the connected computer, reached on [port] (a port descriptor or id). */
    internal fun device(info: DeviceInfo?, port: String?): Device = Device(
        vendor = info?.vendor ?: displayName.substringBefore(' '),
        model = info?.model ?: displayName,
        serial = info?.serial,
        bluetoothAddress = if (byAddress && port != null) "$identityKey@${normalizedAddress(port)}" else legacyKey(info),
    )

    /** The key without an address: all a Shearwater download stored before addresses were used. */
    internal fun legacyKey(info: DeviceInfo?): String = identityKey + (info?.serial?.let { ":$it" } ?: "")

    /** A Bluetooth address as AA:BB:CC:DD:EE:FF whichever way the platform spells it; other ports as they are. */
    private fun normalizedAddress(port: String): String {
        val bare = port.removePrefix("bt:")
        val hex = bare.filter { it != ':' && it != '-' }
        if (hex.length != 12 || !hex.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) return bare
        return hex.uppercase().chunked(2).joinToString(":")
    }
}

/** A download that overlaps an existing dive, shown for an attach-or-keep-separate choice. */
data class MergeReview(val incomingLabel: String, val existingLabel: String)

/**
 * A dive computer already in the logbook that the picker can download from again: its
 * [type], how many dives it has recorded, its newest dive, and the port it was last
 * reached on ([lastPort], a [SerialPortInfo.descriptor]).
 */
data class KnownComputer(
    val device: Device,
    val type: DiveComputerType,
    val diveCount: Long,
    val newestDive: Pair<Long, Int>?,
    val lastPort: String?,
) {
    /** The device identity, the key its connection is remembered under. */
    val key: String get() = device.bluetoothAddress.orEmpty()

    val title: String get() = device.nickname?.takeIf { it.isNotBlank() } ?: device.model

    val subtitle: String get() = ComputerNames.fullName(device) + (device.serial?.let { " #$it" } ?: "")
}

/**
 * How much of the device to pull. [NewOnly] is incremental: the download stops at the
 * newest dive already stored for this device, so a repeat download fetches nothing (and
 * for devices served one dive at a time, reads nothing) past what is already here. The
 * [Latest] options re-read the newest N regardless of what is stored, and [All] re-reads
 * everything; both leave duplicate skipping to the import step.
 */
sealed interface DownloadAmount {
    val label: String

    /** Incremental: only dives newer than the newest already stored. */
    data object NewOnly : DownloadAmount {
        override val label = "New dives only"
    }

    /** The newest [count] dives, whether or not they are already stored. */
    data class Latest(val count: Int) : DownloadAmount {
        override val label = "Latest $count"
    }

    /** Every dive on the device. */
    data object All : DownloadAmount {
        override val label = "All dives"
    }

    companion object {
        /** The options offered in the picker, in order; [NewOnly] is the default. */
        val options: List<DownloadAmount> = listOf(NewOnly, Latest(5), Latest(25), Latest(100), All)
    }
}

/**
 * Platform-agnostic orchestration of a wired download: open a [Transport] for the
 * chosen [DiveComputerType] and port, run that type's protocol over it, parse each raw
 * dive and import it through the shared pipeline (device identity plus dedupe), then
 * return a user-facing summary. The serial/USB specifics live behind [SerialPorts];
 * everything here is shared across targets. [protocolFor] and [parserFor] default to the
 * type's own; tests pass fakes.
 */
class DownloadController(
    private val container: AppContainer,
    private val serialPorts: SerialPorts,
    private val connectionMemory: ConnectionMemory = ConnectionMemory.None,
    private val protocolFor: (DiveComputerType, Transport) -> DiveComputerProtocol = { type, t -> type.protocol(t) },
    private val parserFor: (DiveComputerType) -> DiveLogParser = { it.parser() },
) {
    /**
     * The computers in the logbook that one of [types] can download from, the most
     * recently used first, then by newest dive.
     */
    fun knownComputers(types: List<DiveComputerType>): List<KnownComputer> {
        val counts = container.devices.diveCounts()
        val newest = container.devices.newestDives()
        val last = connectionMemory.lastDevice()
        return container.devices.all().mapNotNull { device ->
            val type = types.firstOrNull { it.owns(device) } ?: return@mapNotNull null
            KnownComputer(
                device = device,
                type = type,
                diveCount = counts[device.id] ?: 0,
                newestDive = newest[device.id],
                lastPort = device.bluetoothAddress?.let { connectionMemory.recall(it) },
            )
        }.sortedWith(compareByDescending<KnownComputer> { it.key == last }.thenByDescending { it.newestDive?.first ?: 0 })
    }

    /** The key of the computer downloaded from most recently, if any. */
    fun lastComputerKey(): String? = connectionMemory.lastDevice()

    /**
     * The id of a port to preselect for [type]: the one a device of this type was last
     * reached on, if it is present in [ports] now. Null when nothing is remembered or the
     * remembered port is not currently available, so the caller can fall back to a guess.
     */
    fun preselectedPortId(type: DiveComputerType, ports: List<SerialPortInfo>): String? {
        val remembered = container.devices.all()
            .filter { type.owns(it) }
            .mapNotNull { device -> device.bluetoothAddress?.let { connectionMemory.recall(it) } }
            .toSet()
        return ports.firstOrNull { it.descriptor in remembered }?.id
    }

    /**
     * Run the download end to end on the IO dispatcher. Raising [cancel], or cancelling the
     * calling coroutine, closes the link so a blocked read ends at once; the dives read
     * until then are still imported and the summary starts with "Cancelled". A download
     * that fails partway likewise imports what it read and says where it stopped.
     * [onProgress] reports a 0..1 fraction and a label. [reviewMerges] is asked once with
     * every dive that overlaps an existing one and returns the indices of those to attach
     * as another computer on that dive; the rest are kept separate. The default keeps
     * everything separate, so a caller that wants the interactive review passes its own.
     */
    suspend fun download(
        type: DiveComputerType,
        portId: String,
        cancel: () -> Boolean,
        onProgress: (fraction: Float, label: String) -> Unit,
        amount: DownloadAmount = DownloadAmount.NewOnly,
        portDescriptor: String? = null,
        recordTo: ((transcript: String) -> Unit)? = null,
        reviewMerges: suspend (List<MergeReview>) -> Set<Int> = { emptySet() },
    ): String = withContext(Dispatchers.IO) {
        val job = coroutineContext.job
        val cancelled = { cancel() || !job.isActive }
        onProgress(0f, "Connecting to the dive computer")
        val opened = try {
            cancelledBy(cancel) { serialPorts.open(portId, type.serialParams) }
        } catch (e: CancellationException) {
            ensureActive()
            return@withContext CANCELLED
        }
        // When a sink is given, observe the wire through a recorder so the exchange can be
        // saved and replayed as a test fixture. The recorder only observes; it is wrapped
        // around the already-open transport, so it is never opened itself.
        val recording = recordTo?.let { RecordingTransport(opened) }
        val transport: Transport = recording ?: opened
        val port = portDescriptor ?: portId
        val session = Session(type, port, amount, onProgress)
        var failure: Exception? = null
        try {
            cancelledBy(cancel) {
                closingOnCancel(transport) { session.run(protocolFor(type, transport), CancellationSignal(cancelled)) }
            }
        } catch (e: CancellationException) {
            ensureActive() // the caller's own cancellation goes on up
            failure = DownloadCancelledException()
        } catch (e: Exception) {
            failure = e
        } finally {
            if (recording != null) runCatching { recordTo(recording.transcript().toText()) }
            runCatching { transport.close() }
        }
        val userCancelled = failure != null && (failure is DownloadCancelledException || cancel())
        if (failure != null && session.received.isEmpty()) {
            if (userCancelled) return@withContext CANCELLED
            throw failure
        }

        onProgress(1f, "Parsing dives")
        val deviceId = resolveDevice(type, session.deviceInfo, port)
        container.devices.get(deviceId)?.bluetoothAddress?.let { key ->
            // Remember where this device was reached, and that it was the last one used, so
            // the picker can preselect both next time.
            if (portDescriptor != null) connectionMemory.remember(key, portDescriptor)
            connectionMemory.rememberLastDevice(key)
        }
        val outcome = importRaws(
            raws = session.received.sortedBy { it.index },
            parser = parserFor(type),
            deviceId = deviceId,
            holdBackAfterUnreadable = amount is DownloadAmount.NewOnly,
            reviewMerges = reviewMerges,
        )
        val stop = when {
            userCancelled -> CANCELLED
            failure != null -> "Download stopped after ${session.received.size} dive${plural(session.received.size)}: " +
                (failure.message ?: failure::class.simpleName)
            else -> null
        }
        summary(outcome, stop, nothingFound = session.received.isEmpty() && session.knownFingerprint == null)
    }

    /** The blocking part of one download, run on the IO dispatcher. Its fields are read once [run] has returned. */
    private inner class Session(
        private val type: DiveComputerType,
        private val port: String,
        private val amount: DownloadAmount,
        private val onProgress: (fraction: Float, label: String) -> Unit,
    ) {
        val received = ArrayList<IndexedValue<RawDive>>()
        var deviceInfo: DeviceInfo? = null
        var knownFingerprint: String? = null

        fun run(protocol: DiveComputerProtocol, cancel: CancellationSignal) {
            // For an incremental download the protocol needs the stop fingerprint up front,
            // which means resolving the device identity before the download. That needs the
            // serial the device reports, so read its info first; the protocol keeps it, so
            // the download does not read it again.
            if (amount is DownloadAmount.NewOnly) {
                onProgress(0f, "Checking the device")
                val info = runCatching { protocol.readDeviceInfo() }.getOrNull()
                knownFingerprint = findDevice(type, info, port)?.let { container.dives.newestFingerprint(it.id) }
            }
            // Devices that read the whole memory report a byte fraction (total > 0); devices
            // served one dive at a time report dive counts instead, so the label follows
            // whichever the protocol gives.
            var diveCount = 0
            val listener = object : DownloadListener {
                override fun onDeviceInfo(info: DeviceInfo) {
                    deviceInfo = info
                }

                override fun onDiveCount(total: Int) {
                    diveCount = total
                    if (total > 0) onProgress(0f, "Downloading dive 1 of $total")
                }

                override fun onDiveDownloaded(index: Int, dive: RawDive) {
                    received += IndexedValue(index, dive)
                    if (diveCount > 0) {
                        val done = received.size
                        val next = (done + 1).coerceAtMost(diveCount)
                        onProgress(done.toFloat() / diveCount, "Downloading dive $next of $diveCount")
                    }
                }

                override fun onProgress(current: Int, total: Int) {
                    if (total > 0) {
                        onProgress(current.toFloat() / total, "Reading memory ${current * 100 / total}%")
                    }
                }
            }
            protocol.download(
                knownFingerprint = knownFingerprint,
                listener = listener,
                cancel = cancel,
                limit = (amount as? DownloadAmount.Latest)?.count,
            )
        }
    }

    /**
     * Run [block], cancelling it like a coroutine cancellation once the user raises
     * [cancel]; the flag is polled, since the caller only sets it.
     */
    private suspend fun <T> cancelledBy(cancel: () -> Boolean, block: suspend () -> T): T = coroutineScope {
        val work = async { block() }
        val watcher = launch {
            while (!cancel()) delay(CANCEL_POLL_MS)
            work.cancel()
        }
        try {
            work.await()
        } finally {
            watcher.cancel()
        }
    }

    /**
     * The stored device that is the computer [type] reached on [port], or null. By its key;
     * else by the key stored before Bluetooth addresses were part of it; else matched like
     * an import (same serial, or the only device of the model), but never a device another
     * computer of this type already identifies as.
     */
    private fun findDevice(type: DiveComputerType, info: DeviceInfo?, port: String): Device? {
        val device = type.device(info, port)
        val key = device.bluetoothAddress
        key?.let { container.devices.byAddress(it) }?.let { return it }
        type.legacyKey(info).takeIf { it != key }?.let { container.devices.byAddress(it) }?.let { return it }
        return container.devices.findMatch(device)?.takeUnless { type.owns(it) && it.bluetoothAddress != key }
    }

    /** The id of the device for this download ([findDevice]), created if new; a match learns its key and serial. */
    private fun resolveDevice(type: DiveComputerType, info: DeviceInfo?, port: String): Long {
        val device = type.device(info, port)
        val found = findDevice(type, info, port) ?: return container.devices.add(device)
        val learned = found.copy(
            serial = found.serial ?: device.serial,
            bluetoothAddress = if (found.bluetoothAddress == null || type.owns(found)) device.bluetoothAddress else found.bluetoothAddress,
        )
        if (learned != found) container.devices.update(learned)
        return found.id
    }

    private class Outcome(val imported: Int, val merged: Int, val skipped: Int, val unreadable: Int, val heldBack: Int)

    /**
     * Parse and import raw dives (newest first) for a device, counting new, merged,
     * already-stored and unreadable ones. Every dive is classified first, so the dives that
     * overlap an existing one go to [reviewMerges] in one batch before anything is imported.
     *
     * A dive the parser cannot read is not stored. With [holdBackAfterUnreadable] (an
     * incremental download) the dives newer than it are not imported either: the next
     * incremental download stops at the newest stored dive, so it then reads the unreadable
     * one again instead of skipping it for good.
     */
    private suspend fun importRaws(
        raws: List<IndexedValue<RawDive>>,
        parser: DiveLogParser,
        deviceId: Long,
        holdBackAfterUnreadable: Boolean,
        reviewMerges: suspend (List<MergeReview>) -> Set<Int>,
    ): Outcome {
        val parsed = raws.map { (index, raw) -> index to runCatching { parser.parse(raw).copy(deviceId = deviceId) }.getOrNull() }
        val newestUnreadable = parsed.filter { it.second == null }.minOfOrNull { it.first }
        val usable = parsed.mapNotNull { (index, dive) ->
            dive?.takeUnless { holdBackAfterUnreadable && newestUnreadable != null && index < newestUnreadable }
        }
        val readable = parsed.count { it.second != null }
        val classified = usable.map { it to container.dives.classify(it) }
        // Positions in [classified] of the dives that overlap an existing one.
        val candidates = classified.indices.filter { classified[it].second is ImportDecision.MergeCandidate }
        val toMerge = if (candidates.isEmpty()) {
            emptySet()
        } else {
            val reviews = candidates.map { i ->
                val (incoming, decision) = classified[i]
                reviewFor(incoming, (decision as ImportDecision.MergeCandidate).diveId)
            }
            val approved = reviewMerges(reviews)
            candidates.filterIndexed { n, _ -> n in approved }.toSet()
        }

        var imported = 0
        var merged = 0
        var skipped = 0
        classified.forEachIndexed { i, (incoming, decision) ->
            when (decision) {
                is ImportDecision.Duplicate -> skipped++
                is ImportDecision.MergeCandidate ->
                    if (i in toMerge) {
                        container.dives.attachToDive(incoming, decision.diveId)
                        merged++
                    } else {
                        container.dives.importAsNewDive(incoming)
                        imported++
                    }
                ImportDecision.NewDive -> {
                    container.dives.importAsNewDive(incoming)
                    imported++
                }
            }
        }
        return Outcome(imported, merged, skipped, unreadable = raws.size - readable, heldBack = readable - usable.size)
    }

    /** Human labels for the overlap review: the incoming dive against the one it overlaps. */
    private fun reviewFor(incoming: IncomingDive, existingDiveId: Long): MergeReview {
        val existing = container.dives.getDive(existingDiveId)
        return MergeReview(
            incomingLabel = diveLabel(
                incoming.number, incoming.startEpochSeconds, incoming.utcOffsetSeconds, incoming.maxDepthMm,
            ),
            existingLabel = existing?.let {
                diveLabel(it.number, it.startEpochSeconds, it.utcOffsetSeconds, it.maxDepthMm)
            } ?: "existing dive",
        )
    }

    private fun diveLabel(number: Int?, startEpochSeconds: Long, utcOffsetSeconds: Int, maxDepthMm: Int?): String =
        (number?.let { "#$it " } ?: "") +
            Format.date(startEpochSeconds, utcOffsetSeconds) + " " +
            Format.depth(maxDepthMm, UnitSystem.METRIC)

    /** [stop] leads when the download ended early; [nothingFound] means a full read found no dives at all. */
    private fun summary(outcome: Outcome, stop: String?, nothingFound: Boolean): String {
        val parts = buildList {
            with(outcome) {
                if (imported > 0) add("imported $imported new dive${plural(imported)}")
                if (merged > 0) add("merged $merged into existing dive${plural(merged)}")
                if (skipped > 0) add("skipped $skipped already in the logbook")
                if (unreadable > 0) add("$unreadable dive${plural(unreadable)} could not be read")
                if (heldBack > 0) add("left $heldBack newer for the next download")
            }
        }
        val result = parts.joinToString(", ").replaceFirstChar { it.uppercase() }
        return when {
            stop != null -> if (parts.isEmpty()) stop else "$stop. $result"
            parts.isNotEmpty() -> result
            nothingFound -> "No dives found on the device"
            else -> "No new dives"
        }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"

    private companion object {
        const val CANCELLED = "Cancelled"
        const val CANCEL_POLL_MS = 100L
    }
}
