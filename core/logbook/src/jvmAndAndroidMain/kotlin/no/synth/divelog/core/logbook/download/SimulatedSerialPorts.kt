package no.synth.divelog.core.logbook.download

import no.synth.divelog.core.divecomputer.suunto.SimulatedVyper2
import no.synth.divelog.core.divecomputer.suunto.SimulatedVyper2Device
import no.synth.divelog.core.divecomputer.transport.SerialParams
import no.synth.divelog.core.divecomputer.transport.Transport

/**
 * Adds a simulated Suunto HelO2 to [real]'s ports, for demos and trying the download
 * flow without hardware (an emulator, a screen recording). Pick "Suunto HelO2 / Vyper2"
 * as the computer. Each command waits [commandDelayMs], so the dozen made-up dives take
 * about as long as a real cable download.
 */
class SimulatedSerialPorts(
    private val real: SerialPorts,
    private val commandDelayMs: Long = 1500,
) : SerialPorts {
    override val downloadSupported = true

    override fun list(): List<SerialPortInfo> = listOf(PORT) + real.list()

    override suspend fun open(id: String, params: SerialParams): Transport =
        if (id == PORT.id) {
            SlowTransport(SimulatedVyper2Device(SimulatedVyper2.demoMemory()), commandDelayMs)
        } else {
            real.open(id, params)
        }

    private class SlowTransport(private val inner: Transport, private val delayMs: Long) : Transport by inner {
        override fun write(data: ByteArray) {
            Thread.sleep(delayMs)
            inner.write(data)
        }
    }

    private companion object {
        val PORT = SerialPortInfo(id = "simulated:helo2", label = "Simulated Suunto HelO2")
    }
}
