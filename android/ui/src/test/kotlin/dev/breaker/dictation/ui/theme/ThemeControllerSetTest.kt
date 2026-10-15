package dev.breaker.dictation.ui.theme

import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The explicit set of one mode: each outcome it can give, the choice it leaves, and who is told. */
class ThemeControllerSetTest {
    private fun storeWith(mode: StoredThemeMode) = FakeSettingsStore(NONDEFAULT.copy(themeMode = mode))

    @Test
    fun `set to a different mode saves that mode and updates the state`() {
        val store = storeWith(StoredThemeMode.SYSTEM)
        val controller = ThemeController(store, systemIsDark = false)
        assertEquals(ThemeOutcome.CHANGED, controller.set(StoredThemeMode.DARK))
        assertEquals(listOf(NONDEFAULT.copy(themeMode = StoredThemeMode.DARK)), store.saves)
        assertEquals(ThemeState(StoredThemeMode.DARK, ShownThemeMode.DARK, loaded = true), controller.state)
    }

    @Test
    fun `set on a light phone to light saves light and not the opposite of the scheme shown`() {
        val store = storeWith(StoredThemeMode.SYSTEM)
        val controller = ThemeController(store, systemIsDark = false)
        assertEquals(ThemeOutcome.CHANGED, controller.set(StoredThemeMode.LIGHT))
        assertEquals(listOf(NONDEFAULT.copy(themeMode = StoredThemeMode.LIGHT)), store.saves)
        assertEquals(ShownThemeMode.LIGHT, controller.state.shown)
    }

    @Test
    fun `set to the mode already stored saves nothing and tells the observers nothing`() {
        val store = storeWith(StoredThemeMode.DARK)
        val controller = ThemeController(store, systemIsDark = false)
        var calls = 0
        controller.addObserver { calls++ }
        assertEquals(ThemeOutcome.UNCHANGED, controller.set(StoredThemeMode.DARK))
        assertEquals(0, store.saveAttempts)
        assertEquals(0, calls)
        assertEquals(ThemeState(StoredThemeMode.DARK, ShownThemeMode.DARK, loaded = true), controller.state)
    }

    @Test
    fun `set that cannot be saved changes nothing and tells nobody and the next try works`() {
        val store = storeWith(StoredThemeMode.LIGHT)
        val controller = ThemeController(store, systemIsDark = false)
        var calls = 0
        controller.addObserver { calls++ }
        val before = controller.state
        store.failSave = true
        assertEquals(ThemeOutcome.COULD_NOT_SAVE, controller.set(StoredThemeMode.SYSTEM))
        assertTrue("the save was never reached", store.saveAttempts >= 1)
        assertEquals(before, controller.state)
        assertEquals(NONDEFAULT.copy(themeMode = StoredThemeMode.LIGHT), store.current)
        assertEquals(0, calls)
        store.failSave = false
        assertEquals(ThemeOutcome.CHANGED, controller.set(StoredThemeMode.SYSTEM))
        assertEquals(1, calls)
    }

    @Test
    fun `set that cannot read the settings changes nothing and says so`() {
        val store = storeWith(StoredThemeMode.LIGHT)
        val controller = ThemeController(store, systemIsDark = false)
        val before = controller.state
        val loadsBefore = store.loadAttempts
        store.failLoad = true
        assertEquals(ThemeOutcome.COULD_NOT_READ, controller.set(StoredThemeMode.DARK))
        assertTrue("the load was never reached", store.loadAttempts > loadsBefore)
        assertEquals(0, store.saveAttempts)
        assertEquals(before, controller.state)
    }
}
