package no.synth.divelog.core.logbook.settings

import no.synth.divelog.core.model.ThemeMode
import kotlin.test.Test
import kotlin.test.assertEquals

class SettingsTest {
    private class MemoryStore : SettingsStore {
        private val values = mutableMapOf<String, String>()
        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) { values[key] = value }
    }

    @Test
    fun themeFollowsTheSystemByDefault() {
        assertEquals(ThemeMode.SYSTEM, AppSettings(MemoryStore()).themeMode)
    }

    @Test
    fun themeIsKeptAcrossAStore() {
        val store = MemoryStore()
        AppSettings(store).themeMode = ThemeMode.DARK
        assertEquals(ThemeMode.DARK, AppSettings(store).themeMode)
    }
}
