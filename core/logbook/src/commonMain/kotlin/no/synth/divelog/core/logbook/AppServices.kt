package no.synth.divelog.core.logbook

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import no.synth.divelog.core.db.AppDatabase
import no.synth.divelog.core.logbook.io.AutoBackup
import no.synth.divelog.core.logbook.io.LogbookBackup
import no.synth.divelog.core.logbook.io.LogbookIo
import no.synth.divelog.core.logbook.settings.AppSettings
import no.synth.divelog.core.logbook.settings.SettingsStore
import no.synth.divelog.core.logbook.sync.CloudSync

/**
 * The long-lived services behind the app, built once per process by the host: the
 * repositories over [database], typed settings over [settingsStore], logbook import and
 * export, the full [backup] and its [autoBackup] bookkeeping, and the [cloud] client (null
 * where there is none, which hides the cloud actions).
 */
class AppServices(private val database: AppDatabase, settingsStore: SettingsStore, val cloud: CloudSync?) {
    val container = AppContainer(database)
    val settings = AppSettings(settingsStore)
    val logbook = LogbookIo(container)
    val backup = LogbookBackup(database)
    val autoBackup = AutoBackup(backup, settings)

    /** Outlives any screen, so work started there finishes even if the screen goes away. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val replacements = MutableStateFlow(0)

    private val changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * Emits after each change to the logbook. The change is marked for the automatic backup first,
     * here rather than in a screen, so one made while no screen is shown is backed up too.
     */
    val logbookChanged: SharedFlow<Unit> = changed.asSharedFlow()

    init {
        scope.launch {
            database.changes().collect {
                autoBackup.markChanged()
                changed.tryEmit(Unit)
            }
        }
    }

    /**
     * Counts the times the whole logbook was replaced (a restore) or emptied, so screens that
     * may name rows that are gone can start over, also when it happened while they were away.
     */
    val logbookReplacements: StateFlow<Int> = replacements.asStateFlow()

    /** Runs [work], which replaces or empties the whole logbook, to the end even if the caller is cancelled. */
    suspend fun replaceLogbook(work: suspend () -> Unit) {
        scope.async {
            work()
            replacements.value++
        }.await()
    }
}
