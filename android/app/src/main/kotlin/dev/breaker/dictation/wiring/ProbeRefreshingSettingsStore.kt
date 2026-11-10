package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.port.SettingsStore

/**
 * Wraps a [SettingsStore] and calls [onServerUrlChanged] only when a save actually changed
 * the server address.
 *
 * [load] passes through. [save] reads the stored serverUrl first, delegates, and only if
 * the save returned normally and the serverUrl text differs from the stored one does it
 * call [onServerUrlChanged]. Unchanged or blank-to-blank: no call. A throwing save: no
 * call, the exception passes on.
 */
internal class ProbeRefreshingSettingsStore(
    private val delegate: SettingsStore,
    private val onServerUrlChanged: () -> Unit,
) : SettingsStore {
    override fun load(): AppSettings = delegate.load()

    override fun save(settings: AppSettings) {
        val before = delegate.load().serverUrl
        delegate.save(settings)
        if (settings.serverUrl != before) {
            onServerUrlChanged()
        }
    }
}
