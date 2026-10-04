package no.synth.divelog.ui.download

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.synth.divelog.core.db.ImportResult
import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DiveLogParser
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.shearwater.PredatorParser
import no.synth.divelog.core.divecomputer.shearwater.ShearwaterPetrelProtocol
import no.synth.divelog.core.divecomputer.shearwater.ShearwaterPredatorProtocol
import no.synth.divelog.core.divecomputer.suunto.SuuntoD9Parser
import no.synth.divelog.core.divecomputer.suunto.SuuntoD9Protocol
import no.synth.divelog.core.divecomputer.suunto.SuuntoFamily
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyperParser
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyperProtocol
import no.synth.divelog.core.divecomputer.transport.Parity
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.model.Device
import no.synth.divelog.ui.AppContainer

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
    SUUNTO_D9(
        displayName = "Suunto HelO2 / D9",
        serialParams = SuuntoFamily.D9.serialParams,
        identityKey = "usb-serial:suunto-d9",
        protocolFactory = { SuuntoD9Protocol(it) },
        parserFactory = { SuuntoD9Parser() },
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

    /** Identity for the connected computer, folding in the serial when the protocol read one. */
    internal fun device(info: DeviceInfo?): Device = Device(
        vendor = info?.vendor ?: displayName.substringBefore(' '),
        model = info?.model ?: displayName,
        serial = info?.serial,
        bluetoothAddress = identityKey + (info?.serial?.let { ":$it" } ?: ""),
    )

    companion object {
        /** The wired serial Suunto families; the only types a USB-serial cable carries. */
        val SUUNTO: List<DiveComputerType> = listOf(SUUNTO_VYPER, SUUNTO_D9)
    }
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
) {
    /**
     * Run the download end to end. Blocking work runs off the main thread. [cancel] is
     * polled during the memory read; [onProgress] reports a 0..1 fraction and a label.
     */
    suspend fun download(
        type: DiveComputerType,
        portId: String,
        cancel: () -> Boolean,
        onProgress: (fraction: Float, label: String) -> Unit,
    ): String = withContext(Dispatchers.Default) {
        onProgress(0f, "Connecting to the dive computer")
        val transport = serialPorts.open(portId, type.serialParams)
        try {
            val protocol = type.protocol(transport)
            var deviceInfo: DeviceInfo? = null
            val listener = object : DownloadListener {
                override fun onDeviceInfo(info: DeviceInfo) {
                    deviceInfo = info
                }

                override fun onProgress(current: Int, total: Int) {
                    if (total > 0) {
                        onProgress(current.toFloat() / total, "Reading memory ${current * 100 / total}%")
                    }
                }
            }
            val raws = protocol.download(
                knownFingerprint = null,
                listener = listener,
                cancel = CancellationSignal { cancel() },
                limit = null,
            )
            onProgress(1f, "Parsing dives")
            val deviceId = container.devices.getOrCreate(type.device(deviceInfo))
            importRaws(raws, type.parser(), deviceId)
        } finally {
            runCatching { transport.close() }
        }
    }

    /** Parse and import raw dives for a device, counting new vs already-stored. */
    private fun importRaws(raws: List<RawDive>, parser: DiveLogParser, deviceId: Long): String {
        var imported = 0
        var skipped = 0
        for (raw in raws) {
            val incoming = runCatching { parser.parse(raw) }.getOrNull()?.copy(deviceId = deviceId) ?: continue
            when (container.dives.import(incoming) { false }) {
                is ImportResult.SkippedDuplicate -> skipped++
                else -> imported++
            }
        }
        return when {
            imported > 0 ->
                "Imported $imported new dive${plural(imported)}" +
                    if (skipped > 0) ", skipped $skipped already in the logbook" else ""
            skipped > 0 -> "No new dives ($skipped already in the logbook)"
            raws.isEmpty() -> "No dives found on the device"
            else -> "No new dives"
        }
    }

    private fun plural(n: Int) = if (n == 1) "" else "s"
}
