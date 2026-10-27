package no.synth.divelog.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import no.synth.divelog.core.logbook.io.BackupFolder
import no.synth.divelog.core.logbook.io.BackupFolders
import java.awt.FileDialog
import java.awt.Frame
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

/**
 * Backup folders are plain directories, so a folder a sync client watches (Google Drive,
 * iCloud Drive, Dropbox) takes the backups off the machine.
 */
internal class DirectoryBackupFolders : BackupFolders {
    override suspend fun pick(): String? = withContext(Dispatchers.IO) { chooseDirectory("Folder for automatic backups")?.absolutePath }

    override fun open(location: String): BackupFolder? = File(location).takeIf { it.isDirectory }?.let(::DirectoryBackupFolder)

    override fun label(location: String): String {
        val home = System.getProperty("user.home")
        return if (location.startsWith(home + File.separator)) "~" + location.removePrefix(home) else location
    }
}

private class DirectoryBackupFolder(private val dir: File) : BackupFolder {
    override fun list(): List<String> = dir.list()?.toList().orEmpty()

    /** Written beside the target and moved over it, so a sync client never picks up half a file. */
    override fun write(name: String, bytes: ByteArray) {
        val part = File(dir, ".$name.part")
        try {
            part.writeBytes(bytes)
            Files.move(part.toPath(), File(dir, name).toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } finally {
            part.delete()
        }
    }

    override fun delete(name: String) {
        File(dir, name).delete()
    }
}

/** The macOS file dialog picks folders when asked to; elsewhere Swing's chooser does. */
private fun chooseDirectory(title: String): File? {
    if (System.getProperty("os.name").startsWith("Mac")) {
        System.setProperty("apple.awt.fileDialogForDirectories", "true")
        try {
            val dialog = FileDialog(null as Frame?, title, FileDialog.LOAD).apply { isVisible = true }
            val parent = dialog.directory ?: return null
            val name = dialog.file ?: return null
            return File(parent, name)
        } finally {
            System.setProperty("apple.awt.fileDialogForDirectories", "false")
        }
    }
    // Swing wants its dialogs on the event thread.
    var chosen: File? = null
    SwingUtilities.invokeAndWait {
        val chooser = JFileChooser().apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chosen = chooser.selectedFile
    }
    return chosen
}
