package dev.breaker.dictation.ui.theme

import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.core.port.SettingsStore

/**
 * The one place every screen entry reads the stored theme.
 *
 * This mirrors [ThemeController.readState]: it reads the settings' stored mode,
 * and on any read failure falls back to "follow the phone" (SYSTEM). That choice
 * is then resolved into a Theme for drawing, so an entry's theme line is simply
 * `storedTheme(settings, systemIsDark)`. A caller never sees the store loaded twice
 * or the SYSTEM literal spread through every screen.
 */
internal fun storedTheme(settings: SettingsStore, systemIsDark: Boolean): Theme {
    val stored = try {
        settings.load().themeMode
    } catch (loadFailure: Exception) {
        StoredThemeMode.SYSTEM
    }
    return Themes.of(shownMode(stored, systemIsDark))
}
