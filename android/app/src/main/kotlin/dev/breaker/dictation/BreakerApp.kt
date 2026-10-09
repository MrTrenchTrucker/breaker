package dev.breaker.dictation

import dev.breaker.dictation.core.port.SettingsStore

/**
 * The application entry; the only place the composition root is built for the
 * running app.
 *
 * It owns a single [dev.breaker.dictation.BreakerCompositionRoot], constructed
 * lazily from the app's files directory, so wiring happens once and only when a
 * store is first requested. That keeps this `Application` holding no window —
 * it merely supplies the context through which the settings view (and the whole
 * feature) is later attached. Nothing here references an activity; the single
 * window in the app belongs to the launcher activity that reads the store out
 * of [settingsStore].
 */
class BreakerApp : android.app.Application() {

    val compositionRoot: dev.breaker.dictation.BreakerCompositionRoot by lazy {
        BreakerCompositionRoot(filesDir)
    }

    /** The app's settings, proxied through the lazily-built composition root. */
    val settingsStore: SettingsStore get() = compositionRoot.settingsStore
}
