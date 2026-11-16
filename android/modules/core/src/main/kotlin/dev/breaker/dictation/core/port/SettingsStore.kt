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

    /**
     * Records [address] as the last good server address, changing no other setting.
     *
     * Called from the probe thread when a stored entry answers, so the next start begins
     * there. The default is a read-modify-write through [load] and [save]; an implementation
     * that serialises [save] under a lock should override it so its read and write are one
     * serialised step. Without that, a user save landing between the read and the write is
     * overwritten by a stale copy of the whole settings object.
     */
    fun recordServerLastGood(address: String) {
        save(load().copy(serverLastGood = address))
    }
}
