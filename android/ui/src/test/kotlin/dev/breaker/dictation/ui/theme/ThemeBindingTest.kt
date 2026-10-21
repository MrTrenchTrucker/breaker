package dev.breaker.dictation.ui.theme

import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Behavioural checks of the [bindThemeChanges] seam. The seam's body is
 * `themes.addObserver { onThemeChanged() }`, so a saved change fires the
 * callback exactly once, and only when something was actually written. These
 * run on the real controller against an in-memory store; no android.* context
 * is constructed, so they are pure Kotlin. Counts use assertEquals, never >=.
 */
class ThemeBindingTest {

    /** A fresh controller over a store from [mode], plus its fire-count wired through the seam. */
    private class Harness(mode: StoredThemeMode) {
        val store = FakeSettingsStore(NONDEFAULT.copy(themeMode = mode))
        var fires = 0
        val controller = ThemeController(store, systemIsDark = false)
        fun bind() {
            // Register the unit callback THROUGH the seam under test; this is what pins the contract.
            bindThemeChanges(controller, { fires++ })
        }
    }

    @Test
    fun `set fires the callback exactly once after a saved change`() {
        val h = Harness(StoredThemeMode.LIGHT)
        h.bind()
        assertEquals(0, h.fires)
        assertEquals(ThemeOutcome.CHANGED, h.controller.set(StoredThemeMode.DARK))
        assertEquals(1, h.fires)
    }

    @Test
    fun `toggle fires the callback exactly once after a saved change`() {
        val h = Harness(StoredThemeMode.LIGHT)
        h.bind()
        assertEquals(0, h.fires)
        assertEquals(ThemeOutcome.CHANGED, h.controller.toggle())
        assertEquals(1, h.fires)
    }

    @Test
    fun `useSystem fires the callback exactly once after a saved change`() {
        val h = Harness(StoredThemeMode.LIGHT)
        h.bind()
        assertEquals(0, h.fires)
        assertEquals(ThemeOutcome.CHANGED, h.controller.useSystem())
        assertEquals(1, h.fires)
    }

    @Test
    fun `a failed save never fires the callback`() {
        val h = Harness(StoredThemeMode.LIGHT)
        h.bind()
        h.store.failSave = true
        assertEquals(ThemeOutcome.COULD_NOT_SAVE, h.controller.set(StoredThemeMode.DARK))
        assertEquals(0, h.fires)
    }

    @Test
    fun `a failed load never fires the callback`() {
        val h = Harness(StoredThemeMode.LIGHT)
        h.store.failLoad = true
        h.bind()
        assertEquals(ThemeOutcome.COULD_NOT_READ, h.controller.toggle())
        assertEquals(0, h.fires)
    }

    @Test
    fun `an unchanged outcome never fires the callback`() {
        val h = Harness(StoredThemeMode.LIGHT)
        h.bind()
        assertEquals(ThemeOutcome.UNCHANGED, h.controller.set(StoredThemeMode.LIGHT))
        assertEquals(0, h.fires)
    }
}
