package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DownloadCancelledException
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.transport.toHex

/**
 * Downloads from a Shearwater Petrel 1. Unlike the Predator, the Petrel keeps a
 * manifest of dives and serves each dive individually, compressed. The dive log
 * records themselves use the same older format the Predator parser reads.
 */
class ShearwaterPetrelProtocol(
    transport: Transport,
    timeoutMs: Long = 3_000,
) : DiveComputerProtocol {
    private val link = ShearwaterLink(transport, timeoutMs)
    private val memory = ShearwaterMemory(link)

    override fun readDeviceInfo(): DeviceInfo = DeviceInfo(vendor = VENDOR, model = "Petrel")

    override fun download(
        knownFingerprint: String?,
        listener: DownloadListener,
        cancel: CancellationSignal,
    ): List<RawDive> {
        listener.onDeviceInfo(DeviceInfo(vendor = VENDOR, model = "Petrel"))
        val entries = readManifest(cancel)
        val fresh = if (knownFingerprint == null) {
            entries
        } else {
            entries.takeWhile { it.fingerprint != knownFingerprint }
        }
        return fresh.mapIndexed { index, entry ->
            if (cancel.isCancelled()) throw DownloadCancelledException()
            val blob = memory.readCompressed(
                baseAddress = DIVE_BASE + entry.address,
                onProgress = { listener.onProgress(it, 0) },
                cancel = cancel,
            )
            listener.onDiveDownloaded(index)
            RawDive(fingerprint = entry.fingerprint, data = blob, formatId = PredatorDump.FORMAT_ID)
        }
    }

    private fun readManifest(cancel: CancellationSignal): List<ManifestEntry> {
        val entries = mutableListOf<ManifestEntry>()
        var address = MANIFEST_ADDR
        var page = 0
        while (page < MAX_PAGES) {
            if (cancel.isCancelled()) throw DownloadCancelledException()
            val bytes = memory.read(address, MANIFEST_PAGE_SIZE, cancel = cancel)
            val (pageEntries, terminated) = parseManifestPage(bytes)
            entries += pageEntries
            if (terminated) break
            address += MANIFEST_PAGE_SIZE
            page++
        }
        return entries
    }

    data class ManifestEntry(val fingerprint: String, val address: Long)

    companion object {
        private const val VENDOR = "Shearwater"
        private const val MANIFEST_ADDR = 0xE0000000L
        private const val DIVE_BASE = 0xC0000000L
        private const val RECORD_SIZE = 32
        private const val RECORDS_PER_PAGE = 48
        private const val MANIFEST_PAGE_SIZE = RECORD_SIZE * RECORDS_PER_PAGE
        private const val MAX_PAGES = 100
        private const val HEADER_VALID = 0xA5C4
        private const val HEADER_DELETED = 0x5A23
        private const val FINGERPRINT_OFFSET = 4
        private const val FINGERPRINT_LEN = 4
        private const val ADDRESS_OFFSET = 20

        /** Parse one manifest page into valid entries; [second] is true at the manifest end. */
        fun parseManifestPage(page: ByteArray): Pair<List<ManifestEntry>, Boolean> {
            val entries = mutableListOf<ManifestEntry>()
            var offset = 0
            while (offset + RECORD_SIZE <= page.size) {
                val header = be16(page, offset)
                when (header) {
                    HEADER_VALID -> entries += ManifestEntry(
                        fingerprint = page.copyOfRange(offset + FINGERPRINT_OFFSET, offset + FINGERPRINT_OFFSET + FINGERPRINT_LEN).toHex(),
                        address = be32(page, offset + ADDRESS_OFFSET),
                    )
                    HEADER_DELETED -> Unit // skip deleted dive
                    else -> return entries to true // terminator
                }
                offset += RECORD_SIZE
            }
            return entries to false
        }

        private fun be16(d: ByteArray, o: Int): Int =
            ((d[o].toInt() and 0xFF) shl 8) or (d[o + 1].toInt() and 0xFF)

        private fun be32(d: ByteArray, o: Int): Long {
            var v = 0L
            for (i in 0 until 4) v = (v shl 8) or (d[o + i].toLong() and 0xFF)
            return v
        }
    }
}
