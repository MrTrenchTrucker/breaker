package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.ThemeMode as StoredThemeMode
import dev.breaker.dictation.ui.screen.Node
import dev.breaker.dictation.ui.screen.Screen
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.screen.ValueField
import dev.breaker.dictation.ui.screen.flatten
import dev.breaker.dictation.ui.testing.NONDEFAULT
import dev.breaker.dictation.ui.theme.ThemeState
import dev.breaker.shared.tokens.ThemeMode as ShownThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The server address as the settings screen draws it and as its edit is made: the row is an input keyed
 * "server_url", a refused text gives no change, and an accepted one changes only the address.
 */
class SettingsServerAddressScreenTest {
    private val screen = SettingsScreen()

    private fun themeOf() = ThemeState(StoredThemeMode.LIGHT, ShownThemeMode.LIGHT, true)

    private fun nodesOf(s: Screen): List<Node> = s.nodes.flatMap { it.flatten() }

    private fun fieldOf(s: Screen): ValueField =
        nodesOf(s).firstOrNull { it.id == "settings.server.field" } as? ValueField
            ?: throw AssertionError("no server field in the settings screen")

    @Test
    fun `the server row is an input keyed server_url that shows the stored address`() {
        val s = screen.render(NONDEFAULT.copy(serverUrl = "https://box.example"), themeOf(), null)
        val field = fieldOf(s)
        assertEquals("server_url", field.entryKey)
        assertEquals("https://box.example", field.text)
        assertFalse("a stored address is not muted", field.muted)
    }

    @Test
    fun `an empty address shows the words for not set and is muted and still keyed`() {
        val s = screen.render(NONDEFAULT.copy(serverUrl = ""), themeOf(), null)
        val field = fieldOf(s)
        assertEquals("(not set)", field.text)
        assertTrue("the words for not set are muted", field.muted)
        assertEquals("server_url", field.entryKey)
    }

    @Test
    fun `a value field made without a key is display only`() {
        assertNull(ValueField("a", "b", false).entryKey)
    }

    @Test
    fun `an accepted address is the one changed field and every other field is kept`() {
        val edit = screen.editFor(ScreenIntent.SetSetting("server_url", "https://box.example"))
        assertNotNull("an accepted address gives a change", edit)
        assertEquals(NONDEFAULT.copy(serverUrl = "https://box.example"), edit?.invoke(NONDEFAULT))
    }

    @Test
    fun `a blank address gives a change that clears the stored address`() {
        val edit = screen.editFor(ScreenIntent.SetSetting("server_url", "  "))
        assertEquals(NONDEFAULT.copy(serverUrl = ""), edit?.invoke(NONDEFAULT))
    }

    @Test
    fun `each refused address gives no change and does not throw`() {
        val typedTexts = listOf(
            "box.example",
            "ftp://box.example",
            "https://box.example:65536",
            "https://u@box.example",
            "https://box.example/a//b",
        )
        for (typed in typedTexts) {
            assertNull("no change for $typed", screen.editFor(ScreenIntent.SetSetting("server_url", typed)))
        }
    }

    @Test
    fun `a wake gesture key given an address value gives no change`() {
        assertNull(screen.editFor(ScreenIntent.SetSetting("wake_gesture_mode", "https://box.example")))
    }
}
