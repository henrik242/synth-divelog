package no.synth.divelog.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import no.synth.divelog.core.logbook.AppServices
import no.synth.divelog.core.logbook.io.BackupInfo
import no.synth.divelog.core.logbook.reason
import kotlin.time.Duration.Companion.minutes

/**
 * Backups: saving, sharing and restoring one, the automatic backup to a folder a minute after
 * the logbook changes, and the reminder for a logbook with no recent backup. A null action
 * means the platform cannot do it.
 */
internal class BackupFlow(
    private val services: AppServices,
    private val hooks: PlatformHooks,
    private val scope: CoroutineScope,
    private val snackbar: SnackbarHostState,
    private val onOpenSettings: () -> Unit,
) {
    private val auto = services.autoBackup
    private val folders = hooks.backupFolders

    var lastBackupEpochSeconds by mutableStateOf(auto.lastBackupEpochSeconds)
        private set
    var folder by mutableStateOf(auto.folder)
        private set

    /** [folder] as the user knows it; looked up off the main thread, so null for a moment. */
    var folderLabel by mutableStateOf<String?>(null)
        private set
    var lastAutomaticEpochSeconds by mutableStateOf(auto.lastWrittenEpochSeconds)
        private set

    /** Why the last automatic backup failed and when, or null if it did not. */
    var automaticFailure by mutableStateOf(auto.lastFailure)
        private set

    /** Signals the automatic backup that the logbook changed. */
    private val changed = Channel<Unit>(Channel.CONFLATED)

    /** Counts folders chosen and turned off, so the automatic backup starts over even for the same folder. */
    private var folderChoices by mutableStateOf(0)

    /** A picked backup and what it holds, while the user confirms the restore. */
    private var pendingRestore by mutableStateOf<Pair<ByteArray, BackupInfo>?>(null)

    /** Saves a backup to a file the user picks; it counts as a backup once saved. */
    val save: (() -> Unit)? = hooks.saveExport?.let { save ->
        {
            scope.launchReported(snackbar, "Backup failed") {
                val file = withContext(Dispatchers.Default) { services.backup.create() }
                if (save(file)) {
                    auto.recordBackup()
                    refresh()
                    "Saved ${file.name}"
                } else {
                    null
                }
            }
        }
    }

    /** Hands a backup to the share sheet, which does not say whether it went anywhere, so it does not count as one. */
    val share: (() -> Unit)? = hooks.shareExport?.let { share ->
        {
            scope.launchReported(snackbar, "Backup failed") {
                share(withContext(Dispatchers.Default) { services.backup.create() })
                null
            }
        }
    }

    val restore: (() -> Unit)? = hooks.pickImportFile?.let { pick ->
        {
            scope.launchReported(snackbar, "Restore failed") {
                pick()?.let { bytes -> pendingRestore = bytes to withContext(Dispatchers.Default) { services.backup.inspect(bytes) } }
                null
            }
        }
    }

    val chooseFolder: (() -> Unit)? = folders?.let { folders ->
        {
            scope.launchReported(snackbar, "Choosing the backup folder failed") {
                val location = folders.pick() ?: return@launchReported null
                folder?.takeIf { it != location }?.let(folders::release)
                auto.useFolder(location)
                refresh()
                folderChoices++
                null
            }
        }
    }

    /** Whether there is anything to show in the backup section. */
    val available: Boolean get() = save != null || share != null || restore != null || chooseFolder != null

    init {
        refresh()
    }

    fun stopAutomatic() {
        folder?.let { folders?.release(it) }
        auto.useFolder(null)
        refresh()
        folderChoices++
    }

    private fun refresh() {
        lastBackupEpochSeconds = auto.lastBackupEpochSeconds
        lastAutomaticEpochSeconds = auto.lastWrittenEpochSeconds
        automaticFailure = auto.lastFailure
        val location = auto.folder
        if (location != folder) folderLabel = null
        folder = location
        // On Android the label is a query to the folder's provider.
        scope.launch { folderLabel = location?.let { withContext(Dispatchers.Default) { folders?.label(it) } } }
    }

    /**
     * Saves the logbook as it is to the backup folder, if there is one, then replaces it with the backup
     * in [bytes]. Both run to the end even if this screen goes away; the app starts over when it is done.
     */
    private fun restoreBackup(bytes: ByteArray) {
        scope.launchReported(snackbar, "Restore failed") {
            services.replaceLogbook {
                folders?.let { folders ->
                    try {
                        auto.writeBeforeRestore(folders)
                    } catch (e: Exception) {
                        throw IllegalStateException("the logbook as it is could not be saved first (${e.reason}), so nothing was restored", e)
                    }
                }
                services.backup.restore(bytes)
            }
            "Restored the backup"
        }
    }

    /** Writes an automatic backup; false, with the reason shown, if it failed. */
    private suspend fun writeAutomatic(): Boolean {
        val folders = folders ?: return false
        val result = runCatching { withContext(Dispatchers.Default) { auto.write(folders) } }
        refresh()
        result.onFailure { snackbar.showSnackbar("Automatic backup failed: ${it.reason}") }
        return result.isSuccess
    }

    /** Wakes the automatic backup when the logbook changes, and reminds once at start. */
    @Composable
    fun Effects() {
        LaunchedEffect(this) {
            services.logbookChanged.collect { changed.trySend(Unit) }
        }
        LaunchedEffect(this, folderChoices) {
            if (folder == null) return@LaunchedEffect
            auto.keepBackingUp(changed, AUTOMATIC_DELAY) { writeAutomatic() }
        }
        LaunchedEffect(this) {
            if (save == null && share == null) return@LaunchedEffect
            val remind = withContext(Dispatchers.Default) { auto.takeReminder(services.container.dives.count()) }
            if (!remind) return@LaunchedEffect
            val text = auto.daysSinceBackup()?.let { "The last backup of your logbook is $it days old" } ?: "Your logbook has no backup yet"
            val result = snackbar.showSnackbar(text, actionLabel = "Back up", withDismissAction = true, duration = SnackbarDuration.Long)
            if (result == SnackbarResult.ActionPerformed) onOpenSettings()
        }
    }

    @Composable
    fun Dialogs() {
        pendingRestore?.let { (bytes, info) ->
            RestoreBackupDialog(
                info,
                savedFirstTo = folder?.let { folderLabel ?: "the backup folder" },
                onRestore = { pendingRestore = null; restoreBackup(bytes) },
                onDismiss = { pendingRestore = null },
            )
        }
    }

    private companion object {
        /** How long the logbook stays unchanged before the automatic backup is written. */
        val AUTOMATIC_DELAY = 1.minutes
    }
}

/**
 * Asks before replacing the logbook with a backup holding [info]. The logbook as it is goes to
 * [savedFirstTo] first, if there is a backup folder; otherwise this cannot be undone.
 */
@Composable
private fun RestoreBackupDialog(info: BackupInfo, savedFirstTo: String?, onRestore: () -> Unit, onDismiss: () -> Unit) {
    val keeping = savedFirstTo?.let { "The logbook as it is now is saved to $it first." }
        ?: "This cannot be undone; save a backup first if you may want the current logbook back."
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Restore backup?") },
        text = {
            Text(
                "The backup holds ${info.dives} ${if (info.dives == 1L) "dive" else "dives"}. Restoring it replaces " +
                    "everything in the logbook: dives, sites, buddies, gear and dive computers. $keeping Settings are kept.",
            )
        },
        confirmButton = {
            TextButton(
                onClick = onRestore,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Restore") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
internal fun rememberBackupFlow(
    services: AppServices,
    hooks: PlatformHooks,
    snackbar: SnackbarHostState,
    onOpenSettings: () -> Unit,
): BackupFlow {
    val scope = rememberCoroutineScope()
    return remember(services, hooks) { BackupFlow(services, hooks, scope, snackbar, onOpenSettings) }
}
