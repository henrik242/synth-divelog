package no.synth.divelog.core.logbook.io

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.Foundation.dataWithContentsOfFile
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull

@OptIn(ExperimentalForeignApi::class)
class BookmarkBackupFolderTest {
    @Test
    fun aBookmarkedFolderTakesBackups() {
        val path = NSTemporaryDirectory().trimEnd('/') + "/backups-${NSUUID().UUIDString}"
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        try {
            val location = bookmarkLocation(NSURL.fileURLWithPath(path))
            // The test process is outside any app, so the system will not coordinate its writes.
            val folder = BookmarkBackupFolder(assertNotNull(bookmarkedFolderUrl(location)), uncoordinated = true)

            folder.write("synth-divelog-backup-2026-10-27.db.gz", byteArrayOf(1, 2, 3))
            folder.write("synth-divelog-backup-2026-10-27.db.gz", byteArrayOf(4, 5))
            assertContentEquals(
                byteArrayOf(4, 5),
                NSData.dataWithContentsOfFile("$path/synth-divelog-backup-2026-10-27.db.gz")?.toByteArray(),
            )
            folder.write("notes.txt", byteArrayOf(6))
            assertEquals(listOf("notes.txt", "synth-divelog-backup-2026-10-27.db.gz"), folder.list().sorted())

            folder.delete("notes.txt")
            assertEquals(listOf("synth-divelog-backup-2026-10-27.db.gz"), folder.list())
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }

    @Test
    fun aWriteTheSystemWillNotCoordinateFails() {
        val path = NSTemporaryDirectory().trimEnd('/') + "/backups-${NSUUID().UUIDString}"
        NSFileManager.defaultManager.createDirectoryAtPath(path, withIntermediateDirectories = true, attributes = null, error = null)
        try {
            val folder = BookmarkBackupFolder(NSURL.fileURLWithPath(path))
            assertFailsWith<IllegalStateException> { folder.write("synth-divelog-backup-2026-10-27.db.gz", byteArrayOf(1)) }
            assertEquals(emptyList(), folder.list())
        } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
        }
    }

    @Test
    fun aBrokenBookmarkFindsNothing() {
        assertNull(bookmarkedFolderUrl("bm90IGEgYm9va21hcms="))
    }
}
