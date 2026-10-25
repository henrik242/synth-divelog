package no.synth.divelog.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.window.ComposeUIViewController
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.logbook.AppServices
import no.synth.divelog.core.logbook.download.IosSerialPorts
import no.synth.divelog.core.logbook.settings.AppSettings
import no.synth.divelog.core.logbook.settings.UserDefaultsStore
import no.synth.divelog.ui.io.pickDocument
import no.synth.divelog.ui.io.shareDocument
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

/** Built once per process, so a new view controller reuses the open database. */
private val services by lazy { AppServices(createDatabase(), UserDefaultsStore(), cloud = null) }

/**
 * iOS entry point: hosts the shared Compose UI in a UIViewController for the Swift app
 * to present. Dive computers download over Bluetooth LE only (Peregrine, Petrel 2); there is
 * no git client, so cloud sync is unavailable. Files are imported through the document
 * picker and exported through the share sheet (Share in the export dialog).
 * [setCrashReporting] switches Crashlytics, which lives on the Swift side.
 */
fun MainViewController(setCrashReporting: (Boolean) -> Unit): UIViewController = ComposeUIViewController {
    val host = LocalUIViewController.current
    val hooks = remember(host) {
        PlatformHooks(
            serialPorts = IosSerialPorts(),
            // Auto-lock would suspend the app and stall the Bluetooth link mid-download.
            onDownloadActive = { UIApplication.sharedApplication.idleTimerDisabled = it },
            pickImportFile = { pickDocument(host) },
            // The share sheet includes Save to Files.
            shareExport = { shareDocument(host, it) },
            setCrashReporting = setCrashReporting,
        )
    }
    SynthDivelogApp(services, hooks)
}

/** The saved crash-report choice, for the Swift side to apply at launch without opening the database. */
fun crashReportingEnabled(): Boolean = AppSettings(UserDefaultsStore()).crashReporting
