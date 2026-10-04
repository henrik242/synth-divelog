package no.synth.divelog.ui.download

import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.transport.JSerialCommTransport

/** Desktop serial layer over jSerialComm: every system serial port is a candidate. */
class DesktopSerialPorts : SerialPorts {
    override val downloadSupported = true

    override fun list(): List<SerialPortInfo> =
        JSerialCommTransport.availablePortNames().map { SerialPortInfo(it, it) }

    override suspend fun open(id: String, params: SerialParams): Transport =
        JSerialCommTransport.byName(id, params).also { it.open() }
}
