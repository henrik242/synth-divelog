package no.synth.divelog.ui

import no.synth.divelog.core.logbook.download.NoSerialPorts
import no.synth.divelog.core.logbook.download.SerialPorts
import no.synth.divelog.core.logbook.io.ExportFile

/**
 * What a platform plugs into the shared app; each default means "not available here".
 * The app calls these from the main thread; a hook that blocks moves off it itself.
 *
 * - [serialPorts]: the serial layer for dive-computer downloads.
 * - [pickImportFile]: picks a file and returns its bytes, or null if cancelled. Null hides file import.
 * - [saveExport]: saves or shares an exported logbook.
 * - [prepareDownload]: runs before the download picker opens (runtime permissions).
 * - [onDownloadActive]: brackets a running download (keep the process awake);
 *   [onDownloadProgress] follows it.
 * - [recordTranscript]: saves a download's wire exchange for replay as a test fixture.
 * - [setCrashReporting]: turns crash reporting on or off. Null hides the setting.
 */
class PlatformHooks(
    val serialPorts: SerialPorts = NoSerialPorts(),
    val pickImportFile: (suspend () -> ByteArray?)? = null,
    val saveExport: suspend (ExportFile) -> Unit = {},
    val prepareDownload: suspend () -> Unit = {},
    val onDownloadActive: (Boolean) -> Unit = {},
    val onDownloadProgress: (label: String) -> Unit = {},
    val recordTranscript: ((transcript: String) -> Unit)? = null,
    val setCrashReporting: ((enabled: Boolean) -> Unit)? = null,
)
