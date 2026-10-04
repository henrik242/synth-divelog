package no.synth.divelog.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.ComposeUIViewController
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.synth.divelog.core.db.DriverFactory
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.ui.io.LogbookIo
import no.synth.divelog.ui.settings.AppSettings
import no.synth.divelog.ui.settings.SettingsStore
import no.synth.divelog.ui.sync.CloudSync
import platform.UIKit.UIViewController

/**
 * iOS entry point: hosts the shared Compose UI in a UIViewController for the Swift
 * app to present. Reuses the shared repositories, settings and cloud sync. Wired
 * download is Android-only; file import/export awaits a native document picker, so
 * import/export on iOS goes through the Subsurface cloud.
 */
fun MainViewController(): UIViewController = ComposeUIViewController {
    val settings = remember { AppSettings(SettingsStore()) }
    val container = remember { AppContainer(DriverFactory().createDatabase()) }
    val logbook = remember { LogbookIo(container) }
    val scope = rememberCoroutineScope()
    var unitSystem by remember { mutableStateOf(settings.unitSystem) }
    var dataVersion by remember { mutableStateOf(0) }
    var status by remember { mutableStateOf<String?>(null) }

    SynthTheme {
        SynthDivelogApp(
            container = container,
            unitSystem = unitSystem,
            onUnitSystemChange = {
                unitSystem = it
                settings.unitSystem = it
            },
            onDownloadClick = {},
            onReparse = {
                scope.launch {
                    val count = withContext(Dispatchers.Default) { logbook.reparseAll() }
                    dataVersion++
                    status = "Re-parsed $count dives"
                }
            },
            cloudEnabled = true,
            initialCloudUrl = settings.cloudUrl,
            initialCloudUsername = settings.cloudUsername,
            initialCloudPassword = settings.cloudPassword,
            onCloudConfigChange = { url, user, pass ->
                settings.cloudUrl = url
                settings.cloudUsername = user
                settings.cloudPassword = pass
            },
            onCloudPull = { url, user, pass ->
                scope.launch {
                    status = runCatching {
                        val text = CloudSync.pull(url, user, pass)
                        withContext(Dispatchers.Default) { logbook.cloudPullMessage(text) }
                    }.getOrElse { "Pull failed: ${it.message ?: it::class.simpleName}" }
                    dataVersion++
                }
            },
            onCloudPush = { url, user, pass ->
                scope.launch {
                    status = runCatching {
                        val format = LogbookIo.formats().first { it.id == "subsurface-xml" }
                        val body = withContext(Dispatchers.Default) { logbook.exportAll(format) }
                        CloudSync.push(url, user, pass, body)
                        "Pushed to the cloud"
                    }.getOrElse { "Push failed: ${it.message ?: it::class.simpleName}" }
                }
            },
            statusMessage = status,
            onStatusShown = { status = null },
            dataVersion = dataVersion,
        )
    }
}
