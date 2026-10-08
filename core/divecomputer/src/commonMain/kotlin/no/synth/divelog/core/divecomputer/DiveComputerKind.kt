package no.synth.divelog.core.divecomputer

import no.synth.divelog.core.divecomputer.shearwater.PredatorDump
import no.synth.divelog.core.divecomputer.shearwater.PredatorParser
import no.synth.divelog.core.divecomputer.shearwater.ShearwaterLink
import no.synth.divelog.core.divecomputer.shearwater.ShearwaterPetrelProtocol
import no.synth.divelog.core.divecomputer.shearwater.ShearwaterPredatorProtocol
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyper2Dump
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyper2Parser
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyper2Protocol
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyperDump
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyperParser
import no.synth.divelog.core.divecomputer.suunto.SuuntoVyperProtocol
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport

/**
 * The dive computers a download can read: the line settings to open the [Transport]
 * with, the protocol to drive over it, and the parser for the raw dives it yields
 * ([formatIds] are the formats those dives carry). The Suunto families are wired serial;
 * the Shearwater models speak Bluetooth Classic SPP, a full-duplex serial stream.
 */
enum class DiveComputerKind(
    val displayName: String,
    val serialParams: SerialParams,
    val formatIds: Set<String>,
    private val protocolFactory: (Transport) -> DiveComputerProtocol,
    private val parserFactory: () -> DiveLogParser,
) {
    /** Old 2400 8O1 family: Zoop, Vyper, Vytec, Cobra, Gekko, Stinger, Mosquito. */
    SUUNTO_VYPER(
        displayName = "Suunto Zoop / Vyper",
        serialParams = SuuntoVyperProtocol.SERIAL_PARAMS,
        formatIds = setOf(SuuntoVyperDump.FORMAT_ID),
        protocolFactory = { SuuntoVyperProtocol(it) },
        parserFactory = { SuuntoVyperParser() },
    ),

    /** Newer 9600 8N1 family: HelO2, Vyper Air, Vyper2, Cobra2/3. */
    SUUNTO_VYPER2(
        displayName = "Suunto HelO2 / Vyper2",
        serialParams = SuuntoVyper2Protocol.SERIAL_PARAMS,
        formatIds = setOf(SuuntoVyper2Dump.FORMAT_ID),
        protocolFactory = { SuuntoVyper2Protocol(it) },
        parserFactory = { SuuntoVyper2Parser() },
    ),
    SHEARWATER_PETREL(
        displayName = "Shearwater Petrel",
        serialParams = ShearwaterLink.SERIAL_PARAMS,
        formatIds = setOf(PredatorParser.PETREL_FORMAT_ID),
        protocolFactory = { ShearwaterPetrelProtocol(it) },
        parserFactory = { PredatorParser() },
    ),
    SHEARWATER_PREDATOR(
        displayName = "Shearwater Predator",
        serialParams = ShearwaterLink.SERIAL_PARAMS,
        formatIds = setOf(PredatorDump.FORMAT_ID),
        protocolFactory = { ShearwaterPredatorProtocol(it) },
        parserFactory = { PredatorParser() },
    ),
    ;

    /** The download protocol over an already-opened [transport]. */
    fun protocol(transport: Transport): DiveComputerProtocol = protocolFactory(transport)

    fun parser(): DiveLogParser = parserFactory()

    companion object {
        /**
         * The parser for a stored raw dive's format, or null for one kept without a parser.
         * Shearwater Cloud files carry newer models' logs ([PredatorParser.PNF_FORMAT_ID]),
         * which the same parser reads.
         */
        fun parserFor(formatId: String): DiveLogParser? =
            if (formatId == PredatorParser.PNF_FORMAT_ID) {
                PredatorParser()
            } else {
                entries.firstOrNull { formatId in it.formatIds }?.parser()
            }
    }
}
