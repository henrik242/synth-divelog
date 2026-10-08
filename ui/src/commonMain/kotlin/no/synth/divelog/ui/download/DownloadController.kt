package no.synth.divelog.ui.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.synth.divelog.core.db.ImportDecision
import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DiveLogParser
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.shearwater.PredatorParser
import no.synth.divelog.core.divecomputer.shearwater.ShearwaterPetrelProtocol
import no.synth.divelog.core.divecomputer.shearwater.ShearwaterPredatorProtocol
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyper2Parser
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyper2Protocol
import no.synth.divelog.core.divecomputer.suunto.SuuntoFamily
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyperParser
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyperProtocol
import no.synth.divelog.core.divecomputer.transport.Parity
import no.synth.divelog.core.divecomputer.transport.RecordingTransport
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.model.ComputerNames
import no.synth.divelog.core.model.Device
import no.synth.divelog.core.model.IncomingDive
import no.synth.divelog.core.model.units.UnitSystem
import no.synth.divelog.ui.AppContainer
import no.synth.divelog.ui.format.Format

/**
 * A dive-computer the shared download can read over a byte [Transport], bundling the
 * line settings to open with, the protocol to drive and the parser for its raw dives.
 * The two Suunto families are wired serial; the two Shearwater models speak Bluetooth
 * Classic SPP, which on desktop surfaces as a normal serial port, so the same serial
 * [Transport] reads them with plain full-duplex line settings.
 *
 * [identityKey] is a stable dedupe key standing in for the device identity when the
 * protocol has no address and no serial (dive dedupe keys on device plus fingerprint).
 */
enum class DiveComputerType(
    val displayName: String,
    val serialParams: SerialParams,
    private val identityKey: String,
    private val protocolFactory: (Transport) -> DiveComputerProtocol,
    private val parserFactory: () -> DiveLogParser,
) {
    SUUNTO_VYPER(
        displayName = "Suunto Zoop / Vyper",
        serialParams = SuuntoFamily.VYPER.serialParams,
        identityKey = "usb-serial:suunto-vyper",
        protocolFactory = { SuuntoVyperProtocol(it) },
        parserFactory = { SuuntoVyperParser() },
    ),
    SUUNTO_VYPER2(
        displayName = "Suunto HelO2 / Vyper2",
        serialParams = SuuntoFamily.VYPER2.serialParams,
        identityKey = "usb-serial:suunto-vyper2",
        protocolFactory = { SuuntoVyper2Protocol(it) },
        parserFactory = { SuuntoVyper2Parser() },
    ),
    SHEARWATER_PETREL(
        displayName = "Shearwater Petrel",
        serialParams = SHEARWATER_SPP_PARAMS,
        identityKey = "serial-spp:shearwater-petrel",
        protocolFactory = { ShearwaterPetrelProtocol(it) },
        parserFactory = { PredatorParser() },
    ),
    SHEARWATER_PREDATOR(
        displayName = "Shearwater Predator",
        serialParams = SHEARWATER_SPP_PARAMS,
        identityKey = "serial-spp:shearwater-predator",
        protocolFactory = { ShearwaterPredatorProtocol(it) },
        parserFactory = { PredatorParser() },
    ),
    ;

    fun protocol(transport: Transport): DiveComputerProtocol = protocolFactory(transport)

    fun parser(): DiveLogParser = parserFactory()

    /** Whether [device] is one this type reads, by its stored identity key. */
    internal fun owns(device: Device): Boolean =
        device.bluetoothAddress?.startsWith(identityKey) == true

    /** Identity for the connected computer, folding in the serial when the protocol read one. */
    internal fun device(info: DeviceInfo?): Device = Device(
        vendor = info?.vendor ?: displayName.substringBefore(' '),
        model = info?.model ?: displayName,
        serial = info?.serial,
        bluetoothAddress = identityKey + (info?.serial?.let { ":$it" } ?: ""),
    )
}

/**
 * Shearwater Classic SPP is a clean full-duplex byte stream, so no half-duplex RTS
 * toggling and no echo discard; the baud is nominal over RFCOMM.
 */
private val SHEARWATER_SPP_PARAMS = SerialParams(
    baudRate = 115200,
    dataBits = 8,
    parity = Parity.NONE,
    stopBits = 1,
    halfDuplex = false,
    dtr = true,
)

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
 * everything here is shared across targets.
 */
class DownloadController(
    private val container: AppContainer,
    private val serialPorts: SerialPorts,
    private val connectionMemory: ConnectionMemory = ConnectionMemory.None,
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
     * Run the download end to end. Blocking work runs off the main thread. [cancel] is
     * polled during the memory read; [onProgress] reports a 0..1 fraction and a label.
     * [reviewMerges] is asked once with every dive that overlaps an existing one and
     * returns the indices of those to attach as another computer on that dive; the rest
     * are kept separate. The default keeps everything separate, so a caller that wants
     * the interactive review passes its own.
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
    ): String = withContext(Dispatchers.Default) {
        onProgress(0f, "Connecting to the dive computer")
        val opened = serialPorts.open(portId, type.serialParams)
        // When a sink is given, observe the wire through a recorder so the exchange can be
        // saved and replayed as a test fixture. The recorder only observes; it is wrapped
        // around the already-open transport, so it is never opened itself.
        val recording = recordTo?.let { RecordingTransport(opened) }
        val transport: Transport = recording ?: opened
        try {
            val protocol = type.protocol(transport)
            // For an incremental download the protocol needs the stop fingerprint up front,
            // which means resolving the device identity before the download. That needs the
            // serial the device reports, so read its info first; readDeviceInfo is cheap and,
            // for the manifest devices where incremental actually saves reads, costs nothing.
            val knownFingerprint = if (amount is DownloadAmount.NewOnly) {
                onProgress(0f, "Checking the device")
                val info = runCatching { protocol.readDeviceInfo() }.getOrNull()
                container.devices.findId(type.device(info))?.let { container.dives.newestFingerprint(it) }
            } else {
                null
            }
            val limit = (amount as? DownloadAmount.Latest)?.count

            var deviceInfo: DeviceInfo? = null
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

                override fun onDiveDownloaded(index: Int) {
                    if (diveCount > 0) {
                        val done = index + 1
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
            val raws = protocol.download(
                knownFingerprint = knownFingerprint,
                listener = listener,
                cancel = CancellationSignal { cancel() },
                limit = limit,
            )
            onProgress(1f, "Parsing dives")
            val device = type.device(deviceInfo)
            val deviceId = container.devices.getOrCreate(device)
            // Remember where this device was reached, and that it was the last one used, so
            // the picker can preselect both next time.
            device.bluetoothAddress?.let { key ->
                if (portDescriptor != null) connectionMemory.remember(key, portDescriptor)
                connectionMemory.rememberLastDevice(key)
            }
            importRaws(raws, type.parser(), deviceId, reviewMerges)
        } finally {
            if (recording != null) {
                runCatching { recordTo(recording.transcript().toText()) }
            }
            runCatching { transport.close() }
        }
    }

    /**
     * Parse and import raw dives for a device, counting new, merged and already-stored.
     * Every dive is classified first, so the dives that overlap an existing one go to
     * [reviewMerges] in one batch before anything is imported.
     */
    private suspend fun importRaws(
        raws: List<RawDive>,
        parser: DiveLogParser,
        deviceId: Long,
        reviewMerges: suspend (List<MergeReview>) -> Set<Int>,
    ): String {
        val classified = raws.mapNotNull { raw ->
            runCatching { parser.parse(raw) }.getOrNull()?.copy(deviceId = deviceId)
                ?.let { it to container.dives.classify(it) }
        }
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
        return summary(imported, merged, skipped, raws.isEmpty())
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

    private fun summary(imported: Int, merged: Int, skipped: Int, noRaws: Boolean): String {
        val parts = buildList {
            if (imported > 0) add("imported $imported new dive${plural(imported)}")
            if (merged > 0) add("merged $merged into existing dive${plural(merged)}")
            if (skipped > 0) add("skipped $skipped already in the logbook")
        }
        return when {
            parts.isNotEmpty() -> parts.joinToString(", ").replaceFirstChar { it.uppercase() }
            noRaws -> "No dives found on the device"
            else -> "No new dives"
        }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
