package no.synth.divelog.ui

import androidx.compose.runtime.remember
import androidx.compose.ui.uikit.LocalUIViewController
import androidx.compose.ui.window.ComposeUIViewController
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.logbook.AppServices
import no.synth.divelog.core.logbook.settings.UserDefaultsStore
import no.synth.divelog.ui.io.pickDocument
import no.synth.divelog.ui.io.shareDocument
import platform.UIKit.UIViewController

/** Built once per process, so a new view controller reuses the open database. */
private val services by lazy { AppServices(createDatabase(), UserDefaultsStore(), cloud = null) }

/**
 * iOS entry point: hosts the shared Compose UI in a UIViewController for the Swift app
 * to present. There is no serial layer or git client, so dive-computer download and cloud
 * sync are unavailable. Files are imported through the document picker and exported
 * through the share sheet.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val host = LocalUIViewController.current
    val hooks = remember(host) {
        PlatformHooks(
            pickImportFile = { pickDocument(host) },
            saveExport = { shareDocument(host, it) },
        )
    }
    SynthDivelogApp(services, hooks)
}
