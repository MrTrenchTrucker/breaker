package dev.breaker.dictation

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.port.SettingsStore
import dev.breaker.dictation.transport.ProbeSelection

/**
 * The one effective-address decision of the app.
 *
 * [ProbeSelection] over the stored list, with the last good address as the start value and the
 * no-answer fallback. The entries are asked IN LIST ORDER, so entry 1 wins whenever it answers
 * and the last good is never tried ahead of the list; [answers] is the probe's per-address
 * answer and it is only ever asked about an entry, never about the last good, which is a start
 * value and a fallback and nothing else.
 *
 * This is the function the composition root's `serverUrl` provider calls, so every reader of the
 * server address reads this one decision. The source gate
 * (`EffectiveAddressSourceGateTest`) fails if any other main file reads `serverUrl` directly.
 */
internal fun effectiveServerUrl(settings: AppSettings, answers: (String) -> Boolean): String =
    ProbeSelection.select(settings.serverAddressList(), settings.serverLastGood, answers)

/**
 * Persists the address the probe answered on, safely.
 *
 * Delegates to [SettingsStore.recordServerLastGood], the store's own method, which changes no
 * field but [AppSettings.serverLastGood]. The file store implements it under the same lock
 * [SettingsStore.save] takes, so its read and write are one serialised step: the probe thread
 * can never overwrite a user save with a stale copy of the whole [AppSettings]. A store that
 * does not override the method keeps the port's read-modify-write default.
 *
 * Best effort: a failed write is swallowed, because a last-good write must never crash the probe
 * thread it runs on.
 */
internal fun persistServerLastGood(settingsStore: SettingsStore, address: String) {
    try {
        settingsStore.recordServerLastGood(address)
    } catch (_: Exception) {
        // Best effort: a failed last-good write must never crash the probe thread.
    }
}
