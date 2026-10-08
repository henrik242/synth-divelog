package no.synth.divelog.core.divecomputer.suunto

import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DiveLogParser
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport

/**
 * Picks the Suunto wired protocol, its serial line settings and its parser for a
 * given device family. This is the USB equivalent of how the Bluetooth download
 * chooses the Petrel vs Predator protocol: the UI offers the families, then opens
 * the serial transport with [SerialParams] and drives [protocol].
 *
 * The two families speak incompatible protocols and need different line settings,
 * so the user (or device detection) selects one before connecting.
 */
enum class SuuntoFamily(
    val displayName: String,
    val serialParams: SerialParams,
    val exampleModels: String,
) {
    /** Old half-duplex 2400 8O1 family: Zoop, Vyper, Vytec, Cobra, Gekko, Stinger, Mosquito. */
    VYPER(
        displayName = "Zoop / Vyper (old family)",
        serialParams = SuuntoVyperProtocol.SERIAL_PARAMS,
        exampleModels = "Zoop, Vyper, Vytec, Cobra, Gekko, Stinger, Mosquito",
    ),

    /** Newer half-duplex 9600 8N1 family: Vyper2, Cobra2, Cobra3, Vyper Air, HelO2. */
    VYPER2(
        displayName = "HelO2 / Vyper2 (new family)",
        serialParams = SuuntoVyper2Protocol.SERIAL_PARAMS,
        exampleModels = "HelO2, Vyper Air, Vyper2, Cobra2/3",
    ),
    ;

    /** Build the download protocol for this family over an already-opened [transport]. */
    fun protocol(transport: Transport, timeoutMs: Long = 3_000): DiveComputerProtocol = when (this) {
        VYPER -> SuuntoVyperProtocol(transport, timeoutMs)
        VYPER2 -> SuuntoVyper2Protocol(transport, timeoutMs)
    }

    /** Parser for the raw dives this family's protocol produces, or null if none yet. */
    fun parser(): DiveLogParser? = when (this) {
        VYPER -> SuuntoVyperParser()
        VYPER2 -> SuuntoVyper2Parser()
    }
}
