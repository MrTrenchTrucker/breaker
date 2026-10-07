package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.port.SettingsStore

/**
 * A [SettingsStore] held in memory that counts its calls and can be told to fail.
 *
 * A failing [save] records nothing and leaves [current] as it was, so a test can
 * tell a swallowed failure from a stored value.
 */
internal class FakeSettingsStore(var current: AppSettings = AppSettings()) : SettingsStore {

    var loadCount = 0
    var saveCount = 0
    val saved = ArrayList<AppSettings>()
    var failLoad: Throwable? = null
    var failSave: Throwable? = null

    override fun load(): AppSettings {
        loadCount++
        failLoad?.let { throw it }
        return current
    }

    override fun save(settings: AppSettings) {
        saveCount++
        failSave?.let { throw it }
        saved.add(settings)
        current = settings
    }
}
