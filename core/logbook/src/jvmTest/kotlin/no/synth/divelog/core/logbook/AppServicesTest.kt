package no.synth.divelog.core.logbook

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import no.synth.divelog.core.db.createDatabase
import no.synth.divelog.core.logbook.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppServicesTest {
    private class MemoryStore : SettingsStore {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) { values[key] = value }
    }

    private val services = AppServices(createDatabase(), MemoryStore(), cloud = null)

    @Test
    fun changesAreMarkedForTheBackupWithNoScreenFollowingThem() = runBlocking {
        // The services start following changes on their own; keep writing until they hear one.
        withTimeout(5_000) {
            var n = 0
            while (!services.autoBackup.pending) {
                services.container.buddies.add("Buddy ${n++}")
                delay(10)
            }
        }
        assertTrue(services.autoBackup.pending)
    }

    @Test
    fun aReplacementFinishesWhenItsCallerIsCancelled() = runBlocking {
        val started = CompletableDeferred<Unit>()
        val caller = launch(Dispatchers.Default) {
            services.replaceLogbook {
                started.complete(Unit)
                delay(200)
                services.container.buddies.add("Restored")
            }
        }
        started.await()
        caller.cancelAndJoin()

        withTimeout(5_000) { services.logbookReplacements.first { it == 1 } }
        assertEquals(listOf("Restored"), services.container.buddies.all().map { it.name })
    }
}
