package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * `ThemeMode` is the user's theme preference as the domain sees it.
 * `SYSTEM` follows the phone's dark mode; `LIGHT` and `DARK` override it.
 * It lives on [AppSettings] with the default `SYSTEM`.
 */
class ThemeModeTest {
    @Test
    fun `the default theme mode is SYSTEM`() {
        assertEquals(ThemeMode.SYSTEM, AppSettings().themeMode)
    }

    @Test
    fun `copying with only the theme mode changes only the theme mode`() {
        val original = AppSettings()
        val changed = original.copy(themeMode = ThemeMode.DARK)
        assertEquals(ThemeMode.DARK, changed.themeMode)
        // Every other field keeps its value.
        assertEquals(original.mode, changed.mode)
        assertEquals(original.modelSize, changed.modelSize)
        assertEquals(original.serverUrl, changed.serverUrl)
        assertEquals(original.apiKeyRef, changed.apiKeyRef)
        assertEquals(original.wakeGestureEnabled, changed.wakeGestureEnabled)
        assertEquals(original.tilePosition, changed.tilePosition)
        assertEquals(original.language, changed.language)
        assertEquals(original.preloadModel, changed.preloadModel)
        assertEquals(original.formattingEnabled, changed.formattingEnabled)
    }
}
