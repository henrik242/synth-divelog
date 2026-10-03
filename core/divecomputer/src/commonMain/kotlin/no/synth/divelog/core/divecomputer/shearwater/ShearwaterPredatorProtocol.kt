// This Source Code Form is subject to the terms of the Mozilla Public
// License, v. 2.0. If a copy of the MPL was not distributed with this
// file, You can obtain one at https://mozilla.org/MPL/2.0/.

package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.transport.Transport

/**
 * Downloads from a Shearwater Predator (and the Petrel 1, which uses the same
 * older log format). Reads the whole profile memory as one uncompressed dump,
 * then extracts the individual dives.
 */
class ShearwaterPredatorProtocol(
    transport: Transport,
    timeoutMs: Long = 3_000,
) : DiveComputerProtocol {
    private val link = ShearwaterLink(transport, timeoutMs)
    private val memory = ShearwaterMemory(link)

    override fun readDeviceInfo(): DeviceInfo {
        // The older models carry their model byte in the memory dump; identity is
        // resolved there rather than from a separate query.
        return DeviceInfo(vendor = VENDOR, model = "Predator")
    }

    override fun download(
        knownFingerprint: String?,
        listener: DownloadListener,
        cancel: CancellationSignal,
    ): List<RawDive> {
        val dump = memory.read(
            baseAddress = BASE_ADDRESS,
            size = MEMORY_SIZE,
            onProgress = { read, total -> listener.onProgress(read, total) },
            cancel = cancel,
        )

        listener.onDeviceInfo(DeviceInfo(vendor = VENDOR, model = "Predator"))

        val all = PredatorDump.extract(dump)
        // Dives are newest first; stop at the one already stored for this device.
        val fresh = if (knownFingerprint == null) {
            all
        } else {
            all.takeWhile { it.fingerprint != knownFingerprint }
        }
        fresh.forEachIndexed { index, _ -> listener.onDiveDownloaded(index) }
        return fresh
    }

    companion object {
        private const val VENDOR = "Shearwater"
        private const val BASE_ADDRESS = 0xDD000000L
        private const val MEMORY_SIZE = 0x20080
    }
}
