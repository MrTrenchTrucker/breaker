package dev.breaker.dictation.ui.render

import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.ThemeController
import dev.breaker.dictation.ui.theme.ThemeOutcome
import dev.breaker.dictation.ui.theme.ThemeState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * One watcher, registered through the binding, is told each saved theme change once and in
 * order, and is told nothing after it has been detached.
 *
 * The controller is the real one and the store is the in-memory fake, so every count is a
 * count of changes the store saved. What this does not show: no Android view is built here,
 * so it does not show that a view repaints; the repaint of the download bar is left to a device.
 */
class ThemeWatchOnceTest {
    @Test
    fun `each saved change reaches the one watcher exactly once and in order`() {
        val store = FakeSettingsStore(NONDEFAULT.copy(themeMode = StoredThemeMode.LIGHT))
        val themes = ThemeController(store, systemIsDark = false)
        val told = mutableListOf<ThemeState>()
        val binding = ObserverBinding(themes) { told.add(it) }
        binding.attach()

        assertEquals(ThemeOutcome.CHANGED, themes.toggle())
        assertEquals(ThemeOutcome.CHANGED, themes.useSystem())

        assertEquals("two saved changes", 2, store.saveAttempts)
        assertEquals(
            "the watcher is told each change once, in order",
            listOf(StoredThemeMode.DARK, StoredThemeMode.SYSTEM),
            told.map { it.stored },
        )
    }

    @Test
    fun `a detached watcher hears nothing after the detach`() {
        val store = FakeSettingsStore(NONDEFAULT.copy(themeMode = StoredThemeMode.LIGHT))
        val themes = ThemeController(store, systemIsDark = false)
        val told = mutableListOf<ThemeState>()
        val binding = ObserverBinding(themes) { told.add(it) }
        binding.attach()
        themes.toggle()
        binding.detach()

        assertEquals(ThemeOutcome.CHANGED, themes.useSystem())

        assertEquals("the change after the detach is still saved", 2, store.saveAttempts)
        assertEquals("the watcher was told only the change before the detach", 1, told.size)
    }
}
