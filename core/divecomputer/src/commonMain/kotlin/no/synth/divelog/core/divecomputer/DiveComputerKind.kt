package no.synth.divelog.core.divecomputer

import no.synth.divelog.core.divecomputer.shearwater.PredatorDump
import no.synth.divelog.core.divecomputer.shearwater.PredatorParser
import no.synth.divelog.core.divecomputer.shearwater.ShearwaterLink
import no.synth.divelog.core.divecomputer.shearwater.ShearwaterPetrelFamilyProtocol
import no.synth.divelog.core.divecomputer.shearwater.ShearwaterPetrel1Protocol
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
 * the Petrel and Predator speak Bluetooth Classic SPP, a full-duplex serial stream, the
 * Peregrine Bluetooth LE, whose transport carries GATT packets (its line settings are
 * unused), and the Petrel 2 either.
 */
enum class DiveComputerKind(
    val displayName: String,
    val serialParams: SerialParams,
    val formatIds: Set<String>,
    private val protocolFactory: (Transport) -> DiveComputerProtocol,
    private val parserFactory: () -> DiveLogParser,
    /** What the name it advertises over Bluetooth LE contains, for a model found by a scan. */
    val bleName: String? = null,
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
        protocolFactory = { ShearwaterPetrel1Protocol(it) },
        parserFactory = { PredatorParser() },
    ),
    SHEARWATER_PREDATOR(
        displayName = "Shearwater Predator",
        serialParams = ShearwaterLink.SERIAL_PARAMS,
        formatIds = setOf(PredatorDump.FORMAT_ID),
        protocolFactory = { ShearwaterPredatorProtocol(it) },
        parserFactory = { PredatorParser() },
    ),

    /** Bluetooth LE only. Newer firmware logs in the typed-record form, older in Petrel blocks. */
    SHEARWATER_PEREGRINE(
        displayName = "Shearwater Peregrine",
        serialParams = ShearwaterLink.SERIAL_PARAMS,
        formatIds = setOf(PredatorParser.PNF_FORMAT_ID, PredatorParser.PETREL_FORMAT_ID),
        protocolFactory = { ShearwaterPetrelFamilyProtocol.peregrine(it) },
        parserFactory = { PredatorParser() },
        bleName = "Peregrine",
    ),

    /** Bluetooth Classic or LE; the Peregrine's protocol over either. */
    SHEARWATER_PETREL_2(
        displayName = "Shearwater Petrel 2",
        serialParams = ShearwaterLink.SERIAL_PARAMS,
        formatIds = setOf(PredatorParser.PNF_FORMAT_ID, PredatorParser.PETREL_FORMAT_ID),
        protocolFactory = { ShearwaterPetrelFamilyProtocol.petrel2(it) },
        parserFactory = { PredatorParser() },
        // Unconfirmed. "Petrel 3" contains it too; the more specific name wins (forAdvertisedName).
        bleName = "Petrel",
    ),

    /** Bluetooth LE only, like the Peregrine; the same protocol. */
    SHEARWATER_PETREL_3(
        displayName = "Shearwater Petrel 3",
        serialParams = ShearwaterLink.SERIAL_PARAMS,
        formatIds = setOf(PredatorParser.PNF_FORMAT_ID, PredatorParser.PETREL_FORMAT_ID),
        protocolFactory = { ShearwaterPetrelFamilyProtocol.petrel3(it) },
        parserFactory = { PredatorParser() },
        bleName = "Petrel 3", // unconfirmed
    ),
    ;

    /**
     * The download protocol over an already-opened [transport]; open matters, as only an
     * open Bluetooth LE link knows its [Transport.maxWriteSize] (Petrel 2).
     */
    fun protocol(transport: Transport): DiveComputerProtocol = protocolFactory(transport)

    fun parser(): DiveLogParser = parserFactory()

    companion object {
        /**
         * The kind a computer advertising [name] over Bluetooth LE is: the one whose [bleName]
         * it contains, the longest such name when several do ("Petrel 3" over "Petrel").
         */
        fun forAdvertisedName(name: String): DiveComputerKind? =
            entries.filter { k -> k.bleName?.let { name.contains(it, ignoreCase = true) } == true }
                .maxByOrNull { it.bleName?.length ?: 0 }

        /**
         * The parser for a stored raw dive's format, or null for one kept without a parser.
         * Newer models' logs ([PredatorParser.PNF_FORMAT_ID]), from a Peregrine, a Petrel 2
         * or a Shearwater Cloud file, go to the same parser.
         */
        fun parserFor(formatId: String): DiveLogParser? =
            entries.firstOrNull { formatId in it.formatIds }?.parser()
    }
}
