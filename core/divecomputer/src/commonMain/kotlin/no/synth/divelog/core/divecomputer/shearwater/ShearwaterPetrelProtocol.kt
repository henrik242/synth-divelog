package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DeviceInfo
import no.synth.divelog.core.divecomputer.DiveComputerProtocol
import no.synth.divelog.core.divecomputer.DownloadCancelledException
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.newestUntil
import no.synth.divelog.core.divecomputer.toHex
import no.synth.divelog.core.divecomputer.transport.Transport
import no.synth.divelog.core.divecomputer.u16be
import no.synth.divelog.core.divecomputer.u32be

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
        limit: Int?,
    ): List<RawDive> {
        listener.onDeviceInfo(DeviceInfo(vendor = VENDOR, model = "Petrel"))
        // The manifest is newest first, so the newest [limit] dives are just the head of
        // the list; capping here skips the slow per-dive reads for the rest.
        val fresh = readManifest(cancel).asSequence().newestUntil(knownFingerprint, limit) { it.fingerprint }.toList()
        listener.onDiveCount(fresh.size)
        // Read oldest first: if the link drops partway, the dives already read sit right
        // after the stored ones, so the next incremental download picks up the rest.
        val dives = arrayOfNulls<RawDive>(fresh.size)
        for (index in fresh.indices.reversed()) {
            if (cancel.isCancelled()) throw DownloadCancelledException()
            val entry = fresh[index]
            val blob = memory.readCompressed(
                baseAddress = DIVE_BASE + entry.address,
                onProgress = { listener.onProgress(it, 0) },
                cancel = cancel,
            )
            val dive = RawDive(fingerprint = entry.fingerprint, data = blob, formatId = PredatorParser.PETREL_FORMAT_ID)
            dives[index] = dive
            listener.onDiveDownloaded(index, dive)
        }
        return dives.filterNotNull()
    }

    private fun readManifest(cancel: CancellationSignal): List<ManifestEntry> {
        // The manifest is uncompressed and read in fixed pages until a terminator
        // record (or an empty page). Only the per-dive reads are compressed.
        // Each manifest page is read from the same address; the device advances
        // its own pointer and serves the next page. The seen-guard stops us if it
        // ever repeats a page (and avoids an endless loop).
        val entries = mutableListOf<ManifestEntry>()
        val seen = HashSet<String>()
        var page = 0
        while (page < MAX_PAGES) {
            if (cancel.isCancelled()) throw DownloadCancelledException()
            val bytes = memory.read(MANIFEST_ADDR, MANIFEST_PAGE_SIZE, cancel = cancel)
            val (pageEntries, terminated) = parseManifestPage(bytes)
            val fresh = pageEntries.filter { seen.add(it.fingerprint) }
            entries += fresh
            if (terminated || fresh.isEmpty()) break
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
                val header = u16be(page, offset)
                when (header) {
                    HEADER_VALID -> entries += ManifestEntry(
                        fingerprint = page.copyOfRange(offset + FINGERPRINT_OFFSET, offset + FINGERPRINT_OFFSET + FINGERPRINT_LEN).toHex(),
                        address = u32be(page, offset + ADDRESS_OFFSET),
                    )
                    HEADER_DELETED -> Unit // skip deleted dive
                    else -> return entries to true // terminator
                }
                offset += RECORD_SIZE
            }
            return entries to false
        }
    }
}
