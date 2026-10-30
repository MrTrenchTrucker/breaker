package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.ThemeMode
import dev.breaker.dictation.core.port.SettingsStore
import java.io.IOException

/**
 * Reads the stored [ThemeMode], falling back to [default] when the store cannot be read.
 *
 * [SettingsStore.load] propagates an [IOException] by design: an unreadable settings file is an
 * error the store leaves for its caller, not a damaged key it silently substitutes. The launch path
 * that paints the window background reads the theme once at create time, so it guards here: a
 * settings file that cannot be read answers the default theme instead of crashing the activity.
 */
internal fun themeModeOrDefault(store: SettingsStore, default: ThemeMode): ThemeMode =
    try {
        store.load().themeMode
    } catch (e: IOException) {
        default
    }
