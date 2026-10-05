package no.synth.divelog.ui.download

import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.transport.JSerialCommTransport

/**
 * Desktop serial layer over jSerialComm. Each system serial port is a candidate, except
 * the macOS "tty.*" dial-in nodes: they alias the matching "cu.*" call-up node but block
 * on open waiting for carrier detect, which jSerialComm does not clear. The port name is
 * stable, so it doubles as the descriptor remembered across sessions.
 */
class DesktopSerialPorts : SerialPorts {
    override val downloadSupported = true

    override fun list(): List<SerialPortInfo> =
        JSerialCommTransport.availablePortNames()
            .filterNot { it.startsWith("tty.") || it.startsWith("/dev/tty.") }
            .map { SerialPortInfo(id = it, label = it) }

    override suspend fun open(id: String, params: SerialParams): Transport =
        JSerialCommTransport.byName(id, params).also { it.open() }
}
