package no.synth.divelog.ui.io

import no.synth.divelog.core.logbook.io.BackupFolder
import no.synth.divelog.core.logbook.io.BackupFolders
import no.synth.divelog.core.logbook.io.BookmarkBackupFolder
import no.synth.divelog.core.logbook.io.bookmarkLocation
import no.synth.divelog.core.logbook.io.bookmarkedFolderUrl
import no.synth.divelog.core.logbook.io.renewedBookmark
import platform.UIKit.UIViewController

/**
 * Backup folders picked in the document picker (On My iPhone, iCloud Drive, another provider)
 * and kept as bookmarks, so the app reaches them again after a restart.
 */
class BookmarkBackupFolders(private val host: UIViewController) : BackupFolders {
    override suspend fun pick(): String? {
        val folder = pickFolder(host) ?: return null
        val started = folder.startAccessingSecurityScopedResource()
        try {
            return bookmarkLocation(folder)
        } finally {
            if (started) folder.stopAccessingSecurityScopedResource()
        }
    }

    override fun open(location: String): BackupFolder? = bookmarkedFolderUrl(location)?.let(::BookmarkBackupFolder)

    override fun label(location: String): String = bookmarkedFolderUrl(location)?.lastPathComponent ?: "the chosen folder"

    override fun renewed(location: String): String? = renewedBookmark(location)
}
