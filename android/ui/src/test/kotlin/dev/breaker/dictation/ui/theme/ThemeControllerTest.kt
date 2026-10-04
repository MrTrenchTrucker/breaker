package dev.breaker.dictation.ui.theme

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The theme controller's reading, mapping, toggle and "follow the phone" actions. Failures are in ThemeControllerFailureTest. */
class ThemeControllerTest {
    private fun storeWith(mode: StoredThemeMode) = FakeSettingsStore(NONDEFAULT.copy(themeMode = mode))

    private class Row(val stored: StoredThemeMode, val phoneDark: Boolean, val shown: ShownThemeMode)

    /** Written out here, apart from the code: stored choice x phone mode -> scheme shown. */
    private val shownTable = listOf(
        Row(StoredThemeMode.SYSTEM, true, ShownThemeMode.DARK),
        Row(StoredThemeMode.SYSTEM, false, ShownThemeMode.LIGHT),
        Row(StoredThemeMode.LIGHT, true, ShownThemeMode.LIGHT),
        Row(StoredThemeMode.LIGHT, false, ShownThemeMode.LIGHT),
        Row(StoredThemeMode.DARK, true, ShownThemeMode.DARK),
        Row(StoredThemeMode.DARK, false, ShownThemeMode.DARK),
    )

    @Test
    fun `a toggle saves the opposite mode with every other field kept and a new controller reads it`() {
        val store = storeWith(StoredThemeMode.LIGHT)
        val controller = ThemeController(store, systemIsDark = false)
        assertEquals(ThemeOutcome.CHANGED, controller.toggle())
        assertEquals(listOf(NONDEFAULT.copy(themeMode = StoredThemeMode.DARK)), store.saves)
        assertEquals(ThemeState(StoredThemeMode.DARK, ShownThemeMode.DARK, true), controller.state)
        val reread = ThemeController(store, systemIsDark = false)
        assertEquals(ShownThemeMode.DARK, reread.state.shown)
    }

    @Test
    fun `two toggles go dark then light with two saves and every other field kept`() {
        val store = storeWith(StoredThemeMode.LIGHT)
        val controller = ThemeController(store, systemIsDark = false)
        assertEquals(ThemeOutcome.CHANGED, controller.toggle())
        assertEquals(ThemeOutcome.CHANGED, controller.toggle())
        assertEquals(
            listOf(
                NONDEFAULT.copy(themeMode = StoredThemeMode.DARK),
                NONDEFAULT.copy(themeMode = StoredThemeMode.LIGHT),
            ),
            store.saves,
        )
        assertEquals(ShownThemeMode.LIGHT, controller.state.shown)
    }

    @Test
    fun `a toggle from follow-the-phone saves the opposite of what the phone shows and never SYSTEM`() {
        val onDarkPhone = storeWith(StoredThemeMode.SYSTEM)
        assertEquals(ThemeOutcome.CHANGED, ThemeController(onDarkPhone, systemIsDark = true).toggle())
        assertEquals(listOf(NONDEFAULT.copy(themeMode = StoredThemeMode.LIGHT)), onDarkPhone.saves)
        val onLightPhone = storeWith(StoredThemeMode.SYSTEM)
        assertEquals(ThemeOutcome.CHANGED, ThemeController(onLightPhone, systemIsDark = false).toggle())
        assertEquals(listOf(NONDEFAULT.copy(themeMode = StoredThemeMode.DARK)), onLightPhone.saves)
        for (saved in onDarkPhone.saves + onLightPhone.saves) {
            assertTrue(saved.themeMode != StoredThemeMode.SYSTEM)
        }
    }

    @Test
    fun `the shown scheme for every stored choice and phone mode, and its token colours`() {
        assertEquals(StoredThemeMode.entries.toSet(), shownTable.map { it.stored }.toSet())
        assertEquals(6, shownTable.size)
        for (row in shownTable) {
            val where = "stored ${row.stored}, phone dark ${row.phoneDark}"
            assertEquals(where, row.shown, shownMode(row.stored, row.phoneDark))
            val controller = ThemeController(storeWith(row.stored), systemIsDark = row.phoneDark)
            assertEquals(where, row.shown, controller.state.shown)
            assertEquals(where, row.stored, controller.state.stored)
            val palette = if (row.shown == ShownThemeMode.DARK) TruckingTokens.DARK else TruckingTokens.LIGHT
            assertEquals(where, palette.bg, Themes.of(controller.state.shown).color(PaletteSlot.BACKGROUND))
        }
    }

    @Test
    fun `reading the settings never writes them`() {
        for (phoneDark in listOf(true, false)) {
            val defaults = FakeSettingsStore(AppSettings())
            val fromDefaults = ThemeController(defaults, systemIsDark = phoneDark)
            assertEquals(
                ThemeState(StoredThemeMode.SYSTEM, if (phoneDark) ShownThemeMode.DARK else ShownThemeMode.LIGHT, true),
                fromDefaults.state,
            )
            assertEquals(0, defaults.saveAttempts)
            assertTrue(defaults.loadAttempts >= 1)
            val system = storeWith(StoredThemeMode.SYSTEM)
            ThemeController(system, systemIsDark = phoneDark)
            assertEquals(0, system.saveAttempts)
        }
    }

    @Test
    fun `follow-the-phone while already stored as SYSTEM saves nothing and tells nobody`() {
        val store = storeWith(StoredThemeMode.SYSTEM)
        val controller = ThemeController(store, systemIsDark = true)
        var calls = 0
        controller.addObserver { calls++ }
        assertEquals(ThemeOutcome.UNCHANGED, controller.useSystem())
        assertEquals(0, store.saveAttempts)
        assertEquals(0, calls)
    }

    @Test
    fun `follow-the-phone from DARK saves SYSTEM once and a second call saves nothing`() {
        val store = storeWith(StoredThemeMode.DARK)
        val controller = ThemeController(store, systemIsDark = false)
        assertEquals(ThemeOutcome.CHANGED, controller.useSystem())
        assertEquals(listOf(NONDEFAULT.copy(themeMode = StoredThemeMode.SYSTEM)), store.saves)
        assertEquals(ThemeOutcome.UNCHANGED, controller.useSystem())
        assertEquals(1, store.saveAttempts)
    }

    @Test
    fun `follow-the-phone compares the stored choice and not the scheme shown`() {
        val store = storeWith(StoredThemeMode.LIGHT)
        val controller = ThemeController(store, systemIsDark = false)
        assertEquals(ThemeOutcome.CHANGED, controller.useSystem())
        assertEquals(listOf(NONDEFAULT.copy(themeMode = StoredThemeMode.SYSTEM)), store.saves)
    }

    @Test
    fun `after follow-the-phone the shown scheme is the phone's`() {
        for (phoneDark in listOf(true, false)) {
            val store = storeWith(if (phoneDark) StoredThemeMode.LIGHT else StoredThemeMode.DARK)
            val controller = ThemeController(store, systemIsDark = phoneDark)
            assertEquals(ThemeOutcome.CHANGED, controller.useSystem())
            assertEquals(StoredThemeMode.SYSTEM, store.saves.single().themeMode)
            val phone = if (phoneDark) ShownThemeMode.DARK else ShownThemeMode.LIGHT
            assertEquals(ThemeState(StoredThemeMode.SYSTEM, phone, true), controller.state)
        }
    }

    @Test
    fun `every action reads the settings again so another writer's change is kept`() {
        val store = storeWith(StoredThemeMode.LIGHT)
        val controller = ThemeController(store, systemIsDark = false)
        store.replaceStored(store.current.copy(language = "fr"))
        assertEquals(ThemeOutcome.CHANGED, controller.toggle())
        assertEquals(NONDEFAULT.copy(themeMode = StoredThemeMode.DARK, language = "fr"), store.saves.single())
    }

    @Test
    fun `when the stored choice already is the target the state is refreshed with no save and no notice to observers`() {
        val store = storeWith(StoredThemeMode.LIGHT)
        val controller = ThemeController(store, systemIsDark = false)
        var calls = 0
        controller.addObserver { calls++ }
        store.replaceStored(store.current.copy(themeMode = StoredThemeMode.DARK))
        assertEquals(ThemeOutcome.UNCHANGED, controller.toggle())
        assertEquals(ThemeState(StoredThemeMode.DARK, ShownThemeMode.DARK, true), controller.state)
        assertEquals(0, store.saveAttempts)
        assertEquals(0, calls)
        assertFalse(store.events.contains("save"))
    }
}
