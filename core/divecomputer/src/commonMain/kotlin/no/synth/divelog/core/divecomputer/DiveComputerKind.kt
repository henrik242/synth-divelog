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
 * the Petrel and Predator speak Bluetooth Classic SPP, a full-duplex serial stream, and the
 * newer Shearwaters Bluetooth LE, whose transport carries GATT packets (its line settings
 * are unused), the Petrel 2, Perdix and NERD Classic too.
 */
enum class DiveComputerKind(
    val displayName: String,
    val serialParams: SerialParams,
    val formatIds: Set<String>,
    private val protocolFactory: (Transport) -> DiveComputerProtocol,
    private val parserFactory: () -> DiveLogParser,
    /** The words a name it advertises over Bluetooth LE holds one of, for a model found by a scan. */
    val bleNames: Set<String> = emptySet(),
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

    /**
     * Every Shearwater since the Petrel 2, over Bluetooth LE or (Petrel 2, Perdix, NERD)
     * Classic; the device names its own model. Newer firmware logs in the typed-record form,
     * older in Petrel blocks.
     */
    SHEARWATER(
        displayName = "Shearwater Perdix / Teric / Peregrine / Petrel 2+",
        serialParams = ShearwaterLink.SERIAL_PARAMS,
        formatIds = setOf(PredatorParser.PNF_FORMAT_ID, PredatorParser.PETREL_FORMAT_ID),
        protocolFactory = { ShearwaterPetrelFamilyProtocol(it) },
        parserFactory = { PredatorParser() },
        // libdivecomputer's names (dc_filter_shearwater, the Predator aside) are these words,
        // some with a suffix: "Petrel 3", "Perdix AI", "NERD 2", "Tern TX", "Peregrine TX".
        bleNames = setOf("Petrel", "Perdix", "NERD", "Teric", "Peregrine", "Tern"),
    ),
    ;

    /**
     * The download protocol over an already-opened [transport]; open matters, as only an
     * open Bluetooth LE link knows its [Transport.maxWriteSize] (newer Shearwaters).
     */
    fun protocol(transport: Transport): DiveComputerProtocol = protocolFactory(transport)

    fun parser(): DiveLogParser = parserFactory()

    /** Whether a computer advertising [name] over Bluetooth LE is this kind: a word of it is one of [bleNames]. */
    fun advertises(name: String): Boolean =
        name.split(' ', '-', '_').any { word -> bleNames.any { it.equals(word, ignoreCase = true) } }

    companion object {
        /** The kind a computer advertising [name] over Bluetooth LE is, if any ([advertises]). */
        fun forAdvertisedName(name: String): DiveComputerKind? = entries.firstOrNull { it.advertises(name) }

        /**
         * The parser for a stored raw dive's format, or null for one kept without a parser.
         * Newer models' logs ([PredatorParser.PNF_FORMAT_ID]), from a download or a Shearwater
         * Cloud file, go to the same parser.
         */
        fun parserFor(formatId: String): DiveLogParser? =
            entries.firstOrNull { formatId in it.formatIds }?.parser()
    }
}
