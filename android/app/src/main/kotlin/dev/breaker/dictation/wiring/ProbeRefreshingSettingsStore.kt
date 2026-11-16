package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.port.SettingsStore

/**
 * Wraps a [SettingsStore] and calls [onServerUrlChanged] only when a save actually changed
 * the ordered server address list.
 *
 * [load] passes through. [save] reads the stored entries first, delegates, and only if
 * the save returned normally and the ENTRIES differ from the stored ones does it call
 * [onServerUrlChanged]. Unchanged or blank-to-blank: no call. A throwing save: no
 * call, the exception passes on.
 *
 * The comparison is over [AppSettings.serverAddressList], not [AppSettings.serverUrl]:
 * the two are kept equal on write, so a user edit is still seen, while a save that changes
 * only `serverLastGood` - the probe thread recording the address that answered - cannot
 * start another refresh. Comparing `serverUrl` alone would miss an edit to entry 2 or 3.
 */
internal class ProbeRefreshingSettingsStore(
    private val delegate: SettingsStore,
    private val onServerUrlChanged: () -> Unit,
) : SettingsStore {
    override fun load(): AppSettings = delegate.load()

    override fun save(settings: AppSettings) {
        val before = delegate.load().serverAddressList()
        delegate.save(settings)
        if (settings.serverAddressList() != before) {
            onServerUrlChanged()
        }
    }
}
