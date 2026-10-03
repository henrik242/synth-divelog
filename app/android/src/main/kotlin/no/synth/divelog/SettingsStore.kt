package no.synth.divelog

import android.content.Context
import no.synth.divelog.core.model.units.UnitSystem

/** Small persistent store for app preferences. */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("synth-divelog", Context.MODE_PRIVATE)

    var unitSystem: UnitSystem
        get() = runCatching { UnitSystem.valueOf(prefs.getString(KEY_UNITS, null) ?: "") }
            .getOrDefault(UnitSystem.METRIC)
        set(value) { prefs.edit().putString(KEY_UNITS, value.name).apply() }

    private companion object {
        const val KEY_UNITS = "unit_system"
    }
}
