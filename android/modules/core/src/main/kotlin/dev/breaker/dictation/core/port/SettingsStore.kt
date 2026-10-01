package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.AppSettings

/**
 * Reads and writes the user's settings.
 *
 * Implemented by the settings module, which owns the storage and keeps the
 * API key reference in the platform keystore rather than in the settings file.
 */
interface SettingsStore {
    /** The current settings; defaults when nothing has been stored yet. */
    fun load(): AppSettings

    /** Persist [settings]. */
    fun save(settings: AppSettings)
}
