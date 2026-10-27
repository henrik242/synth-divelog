package no.synth.divelog.ui

import no.synth.divelog.core.logbook.download.DiveComputerType
import no.synth.divelog.core.logbook.download.NoSerialPorts
import no.synth.divelog.core.logbook.download.SerialPorts
import no.synth.divelog.core.logbook.io.BackupFolders
import no.synth.divelog.core.logbook.io.ExportFile

/**
 * What a platform plugs into the shared app; each default means "not available here".
 * The app calls these from the main thread; a hook that blocks moves off it itself.
 *
 * - [serialPorts]: the serial layer for dive-computer downloads.
 * - [pickImportFile]: picks a file and returns its bytes, or null if cancelled. Null hides file import.
 * - [saveExport]: saves an exported logbook to a file the user picks; false if cancelled.
 * - [shareExport]: hands an exported logbook to another app (a share sheet).
 *   Null hides that button in the export dialog; with both null there is no export.
 * - [backupFolders]: picks and reaches a folder for automatic backups. Null hides automatic backup.
 * - [prepareDownload]: runs before the download picker opens (runtime permissions).
 * - [downloadHint]: a platform's connection note, shown in the picker after the shared one (`connectHint`).
 * - [onDownloadActive]: brackets a running download (keep the process awake);
 *   [onDownloadProgress] follows it.
 * - [recordTranscript]: saves a download's wire exchange for replay as a test fixture.
 * - [setCrashReporting]: turns crash reporting on or off. Null hides the setting.
 */
class PlatformHooks(
    val serialPorts: SerialPorts = NoSerialPorts(),
    val pickImportFile: (suspend () -> ByteArray?)? = null,
    val saveExport: (suspend (ExportFile) -> Boolean)? = null,
    val shareExport: (suspend (ExportFile) -> Unit)? = null,
    val backupFolders: BackupFolders? = null,
    val prepareDownload: suspend () -> Unit = {},
    val downloadHint: (DiveComputerType) -> String? = { null },
    val onDownloadActive: (Boolean) -> Unit = {},
    val onDownloadProgress: (label: String) -> Unit = {},
    val recordTranscript: ((transcript: String) -> Unit)? = null,
    val setCrashReporting: ((enabled: Boolean) -> Unit)? = null,
)
