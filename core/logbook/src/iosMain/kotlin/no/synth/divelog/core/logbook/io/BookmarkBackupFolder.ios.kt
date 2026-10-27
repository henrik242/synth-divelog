package no.synth.divelog.core.logbook.io

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.BooleanVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCObjectVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.value
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSFileCoordinator
import platform.Foundation.NSFileCoordinatorWritingForDeleting
import platform.Foundation.NSFileCoordinatorWritingForReplacing
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import platform.Foundation.base64EncodedStringWithOptions
import platform.Foundation.create
import platform.Foundation.writeToURL

// A backup folder on iOS is one the user picked in the document picker (On My iPhone, iCloud Drive,
// another provider), kept as a bookmark so the app reaches it again after a restart; its location
// is the bookmark in base64. The picker is in :ui; the folder is here, where it can be tested.

/** The location to keep for [folder]. Call it with the folder's access started, as the picker hands it over. */
@OptIn(ExperimentalForeignApi::class)
fun bookmarkLocation(folder: NSURL): String {
    val data = memScoped {
        val error = alloc<ObjCObjectVar<NSError?>>()
        folder.bookmarkDataWithOptions(0u, includingResourceValuesForKeys = null, relativeToURL = null, error = error.ptr)
            ?: error("Cannot keep the folder: ${error.value?.localizedDescription}")
    }
    return data.base64EncodedStringWithOptions(0u)
}

/** The folder the bookmark [location] names, or null if it can no longer be found. */
fun bookmarkedFolderUrl(location: String): NSURL? = resolveBookmark(location)?.first

/**
 * A fresh location for the folder [location] names when iOS says the bookmark is going stale (the
 * folder moved, or the device was restored), or null when it is fine or cannot be renewed.
 */
fun renewedBookmark(location: String): String? {
    val (folder, stale) = resolveBookmark(location) ?: return null
    if (!stale) return null
    val started = folder.startAccessingSecurityScopedResource()
    try {
        return runCatching { bookmarkLocation(folder) }.getOrNull()
    } finally {
        if (started) folder.stopAccessingSecurityScopedResource()
    }
}

/** The folder the bookmark [location] names and whether the bookmark is going stale. */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun resolveBookmark(location: String): Pair<NSURL, Boolean>? {
    val data = NSData.create(base64EncodedString = location, options = 0u) ?: return null
    return memScoped {
        val stale = alloc<BooleanVar>()
        NSURL.URLByResolvingBookmarkData(data, options = 0u, relativeToURL = null, bookmarkDataIsStale = stale.ptr, error = null)
            ?.let { it to stale.value }
    }
}

/**
 * A [BackupFolder] over a bookmarked folder; each call opens the folder's access and closes it again.
 * [uncoordinated] lets a process the system will not coordinate for (a test, outside any app) write anyway.
 */
@OptIn(ExperimentalForeignApi::class)
class BookmarkBackupFolder(private val folder: NSURL, private val uncoordinated: Boolean = false) : BackupFolder {
    override fun list(): List<String> = access {
        NSFileManager.defaultManager.contentsOfDirectoryAtPath(path(), error = null).orEmpty().filterIsInstance<String>()
    }

    /** Written atomically, coordinated with the file provider (iCloud Drive) that may be syncing it. */
    override fun write(name: String, bytes: ByteArray) = access {
        val target = requireNotNull(folder.URLByAppendingPathComponent(name)) { "Bad file name $name" }
        var written = false
        coordinate(target, NSFileCoordinatorWritingForReplacing) { url -> written = bytes.toNSData().writeToURL(url, atomically = true) }
        check(written) { "Cannot write $name in the backup folder" }
    }

    override fun delete(name: String) = access {
        val target = folder.URLByAppendingPathComponent(name) ?: return@access
        coordinate(target, NSFileCoordinatorWritingForDeleting) { url -> NSFileManager.defaultManager.removeItemAtURL(url, error = null) }
    }

    private fun path(): String = requireNotNull(folder.path) { "The backup folder has no path" }

    private fun <T> access(block: () -> T): T {
        val started = folder.startAccessingSecurityScopedResource()
        try {
            return block()
        } finally {
            if (started) folder.stopAccessingSecurityScopedResource()
        }
    }

    /**
     * Runs [write] coordinated with the folder's file provider, so it does not race the provider's
     * syncing. A provider that refuses fails the write, unless the folder is [uncoordinated].
     */
    private fun coordinate(target: NSURL, options: ULong, write: (NSURL) -> Unit) {
        var ran = false
        val refusal = memScoped {
            val error = alloc<ObjCObjectVar<NSError?>>()
            NSFileCoordinator(filePresenter = null).coordinateWritingItemAtURL(target, options, error.ptr) { url ->
                ran = true
                url?.let(write)
            }
            error.value?.localizedDescription
        }
        if (ran) return
        if (!uncoordinated) throw IllegalStateException("the folder's provider refused the write: ${refusal ?: "no reason given"}")
        write(target)
    }
}
