package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.screen.Label
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.FakeSettingsStore
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.ThemeController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The settings handler's set-mode intent: each name reaches the controller as its own mode, and a name that is not exact is refused. */
class SettingsIntentHandlerThemeTest {
    private class Fixture(held: AppSettings) {
        val store = FakeSettingsStore(held)
        val handler = SettingsIntentHandler(store, ThemeController(store, systemIsDark = false), SettingsScreen())
    }

    private fun noticeText(result: Rendered): String? =
        result.screen.nodes.flatMap { it.flatten() }
            .filterIsInstance<Label>().firstOrNull { it.id == "settings.notice" }?.text

    @Test
    fun `each theme mode name reaches the theme controller as exactly that mode`() {
        val cases = listOf(
            "SYSTEM" to StoredThemeMode.SYSTEM,
            "LIGHT" to StoredThemeMode.LIGHT,
            "DARK" to StoredThemeMode.DARK,
        )
        for ((name, mode) in cases) {
            // Start from a different stored choice, so the request is a real change.
            val start = if (mode == StoredThemeMode.DARK) StoredThemeMode.LIGHT else StoredThemeMode.DARK
            val fixture = Fixture(NONDEFAULT.copy(themeMode = start))
            val after = fixture.handler.handle(ScreenIntent.SetThemeMode(name))
            assertEquals("stored after $name", mode, fixture.store.current.themeMode)
            assertEquals("saved once for $name", 1, fixture.store.saveAttempts)
            assertNull("no message after $name", noticeText(after))
        }
    }

    @Test
    fun `a theme mode name that is not exact is refused with nothing read and nothing written`() {
        for (name in listOf("dark", "Dark", "light", "NOPE", "")) {
            val fixture = Fixture(NONDEFAULT)
            val loadsBefore = fixture.store.loadAttempts
            val after = fixture.handler.handle(ScreenIntent.SetThemeMode(name))
            assertEquals("notice for '$name'", Notice.NOT_ACCEPTED.text, noticeText(after))
            assertEquals("no read for '$name'", loadsBefore, fixture.store.loadAttempts)
            assertEquals("no write for '$name'", 0, fixture.store.saveAttempts)
            assertEquals(NONDEFAULT, fixture.store.current)
        }
    }
}
