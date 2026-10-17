package dev.breaker.dictation.ui.theme

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared reader reads the settings' stored mode and resolves it to a Theme,
 * falling back to "follow the phone" (SYSTEM) when the store cannot be read.
 */
class StoredThemeTest {

    private val light = FakeSettingsStore(AppSettings(themeMode = StoredThemeMode.LIGHT))
    private val dark = FakeSettingsStore(AppSettings(themeMode = StoredThemeMode.DARK))
    private val system = FakeSettingsStore(AppSettings(themeMode = StoredThemeMode.SYSTEM))

    @Test
    fun `a light store draws light even on a dark phone`() {
        assertEquals(ShownThemeMode.LIGHT, storedTheme(light, false).mode)
    }

    @Test
    fun `a dark store draws dark on both phones`() {
        assertEquals(ShownThemeMode.DARK, storedTheme(dark, false).mode)
        assertEquals(ShownThemeMode.DARK, storedTheme(dark, true).mode)
    }

    @Test
    fun `a system store follows the phone's own reading when drawn`() {
        assertEquals(ShownThemeMode.LIGHT, storedTheme(system, false).mode)
        assertEquals(ShownThemeMode.DARK, storedTheme(system, true).mode)
    }

    @Test
    fun `a failing store falls back to SYSTEM and draws dark on a dark phone`() {
        val failing = FakeSettingsStore(AppSettings(themeMode = StoredThemeMode.LIGHT))
        failing.failLoad = true
        assertEquals(ShownThemeMode.DARK, storedTheme(failing, true).mode)
    }

    @Test
    fun `a failing store reaches its load once and keeps the stored choice`() {
        val failing = FakeSettingsStore(AppSettings(themeMode = StoredThemeMode.LIGHT))
        failing.failLoad = true
        storedTheme(failing, true)
        assertEquals(1, failing.loadAttempts)
        assertTrue(failing.failLoad)
    }

    @Test
    fun `a light store drawn on a dark phone still draws light`() {
        assertEquals(ShownThemeMode.LIGHT, storedTheme(light, true).mode)
    }

    @Test
    fun `a failing store follows a light phone and draws light`() {
        val failing = FakeSettingsStore(AppSettings(themeMode = StoredThemeMode.DARK))
        failing.failLoad = true
        assertEquals(ShownThemeMode.LIGHT, storedTheme(failing, false).mode)
    }

    @Test
    fun `a failing store follows a dark phone whatever the stored mode was`() {
        val failing = FakeSettingsStore(AppSettings(themeMode = StoredThemeMode.LIGHT))
        failing.failLoad = true
        assertEquals(ShownThemeMode.DARK, storedTheme(failing, true).mode)
    }
}
