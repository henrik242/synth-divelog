package no.synth.divelog.core.divecomputer.shearwater

import no.synth.divelog.core.divecomputer.CancellationSignal
import no.synth.divelog.core.divecomputer.DownloadCancelledException
import no.synth.divelog.core.divecomputer.DownloadListener
import no.synth.divelog.core.divecomputer.RawDive
import no.synth.divelog.core.divecomputer.newestUntil
import no.synth.divelog.core.divecomputer.toHex
import no.synth.divelog.core.divecomputer.u16be
import no.synth.divelog.core.divecomputer.u32be

/** The vendor name every Shearwater protocol reports. */
internal const val SHEARWATER = "Shearwater"

/**
 * The manifest walk and per-dive compressed reads the Petrel 1, Petrel 2 and Peregrine share: read
 * the manifest pages, then each dive newer than the known one from a dive base plus its
 * manifest address, tagged with the log's format id.
 */
internal class ShearwaterLogbook(private val memory: ShearwaterMemory) {
    fun download(
        diveBase: Long,
        formatId: String,
        knownFingerprint: String?,
        listener: DownloadListener,
        cancel: CancellationSignal,
        limit: Int?,
    ): List<RawDive> {
        // The manifest is newest first, so the newest [limit] dives are just the head of
        // the list; capping here skips the slow per-dive reads for the rest.
        val fresh = readManifest(knownFingerprint, cancel).asSequence()
            .newestUntil(knownFingerprint, limit) { it.fingerprint }.toList()
        listener.onDiveCount(fresh.size)
        // Read oldest first: if the link drops partway, the dives already read sit right
        // after the stored ones, so the next incremental download picks up the rest.
        val dives = arrayOfNulls<RawDive>(fresh.size)
        for (index in fresh.indices.reversed()) {
            if (cancel.isCancelled()) throw DownloadCancelledException()
            val entry = fresh[index]
            val blob = memory.readCompressed(
                baseAddress = diveBase + entry.address,
                onProgress = { listener.onProgress(it, 0) },
                cancel = cancel,
            )
            val dive = RawDive(fingerprint = entry.fingerprint, data = blob, formatId = formatId)
            dives[index] = dive
            listener.onDiveDownloaded(index, dive)
        }
        return dives.filterNotNull()
    }

    /**
     * The manifest entries up to [knownFingerprint] (no further pages are read once it is
     * seen). The manifest is uncompressed and read in fixed pages from the same address:
     * the device advances its own pointer and serves the next page. It ends at a
     * terminator record; a page of deleted records alone does not end it, as dives may
     * follow. The seen-guard stops a page the device repeats, and [MAX_PAGES] any loop.
     */
    private fun readManifest(knownFingerprint: String?, cancel: CancellationSignal): List<ManifestEntry> {
        val entries = mutableListOf<ManifestEntry>()
        val seen = HashSet<String>()
        for (page in 0 until MAX_PAGES) {
            if (cancel.isCancelled()) throw DownloadCancelledException()
            val (pageEntries, terminated) = parseManifestPage(memory.read(MANIFEST_ADDR, PAGE_SIZE, cancel = cancel))
            val fresh = pageEntries.filter { seen.add(it.fingerprint) }
            entries += fresh
            val repeated = pageEntries.isNotEmpty() && fresh.isEmpty()
            if (terminated || repeated || fresh.any { it.fingerprint == knownFingerprint }) break
        }
        return entries
    }

    data class ManifestEntry(val fingerprint: String, val address: Long)

    companion object {
        /** Where the Predator-like dive blocks live; manifest addresses are relative to it. */
        const val PREDATOR_LIKE_BASE = 0xC0000000L

        private const val MANIFEST_ADDR = 0xE0000000L
        private const val RECORD_SIZE = 32
        private const val RECORDS_PER_PAGE = 48
        private const val PAGE_SIZE = RECORD_SIZE * RECORDS_PER_PAGE
        private const val MAX_PAGES = 100
        private const val HEADER_VALID = 0xA5C4
        private const val HEADER_DELETED = 0x5A23
        private const val FINGERPRINT_OFFSET = 4
        private const val FINGERPRINT_LEN = 4
        private const val ADDRESS_OFFSET = 20

        /** Parse one manifest page into valid entries; the flag is true at the manifest end. */
        fun parseManifestPage(page: ByteArray): Pair<List<ManifestEntry>, Boolean> {
            val entries = mutableListOf<ManifestEntry>()
            var offset = 0
            while (offset + RECORD_SIZE <= page.size) {
                when (u16be(page, offset)) {
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
