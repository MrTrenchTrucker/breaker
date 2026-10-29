package dev.breaker.dictation.ui.screen.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.ShakeWakeMode
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.ui.screen.ScreenIntent
import dev.breaker.dictation.ui.testing.NONDEFAULT
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Which intents the settings screen turns into a change, and which it refuses.
 *
 * The screen draws a control for three switches, the routing mode and the shake to wake choice, so a
 * request for anything else has no row behind it and is refused rather than
 * applied. A value other than the exact text a switch sends is refused as well:
 * a half-understood request would be saved as though the user had made it.
 */
class SettingsScreenEditTest {
    private val screen = SettingsScreen()

    /** The result of applying [intent] to [settings], or null when the screen refuses it. */
    private fun applied(intent: ScreenIntent, settings: AppSettings = NONDEFAULT): AppSettings? =
        screen.editFor(intent)?.invoke(settings)

    @Test
    fun `each switch key changes exactly its own field and keeps every other field`() {
        val cases = listOf(
            Triple("preloadModel", true, NONDEFAULT.copy(preloadModel = true)),
            Triple("preloadModel", false, NONDEFAULT.copy(preloadModel = false)),
            Triple("showTileOnKeyboard", true, NONDEFAULT.copy(showTileOnKeyboard = true)),
            Triple("showTileOnKeyboard", false, NONDEFAULT.copy(showTileOnKeyboard = false)),
            Triple("formattingEnabled", true, NONDEFAULT.copy(formattingEnabled = true)),
            Triple("formattingEnabled", false, NONDEFAULT.copy(formattingEnabled = false)),
        )
        for ((key, value, expected) in cases) {
            assertEquals(
                "key $key set to $value",
                expected,
                applied(ScreenIntent.SetSetting(key, value.toString())),
            )
        }
    }

    @Test
    fun `a routing mode changes the mode and keeps every other field`() {
        for (mode in SttMode.entries) {
            assertEquals(
                "mode $mode",
                NONDEFAULT.copy(mode = mode),
                applied(ScreenIntent.SetRoutingMode(mode.name)),
            )
        }
    }

    @Test
    fun `a change is a copy of the settings handed in, not the defaults`() {
        val otherUrl = "https://other.example.invalid"
        val held = NONDEFAULT.copy(language = "fr", serverUrl = otherUrl, preloadModel = true)
        val changed = applied(ScreenIntent.SetSetting("preloadModel", "false"), held)
        assertEquals(held.copy(preloadModel = false), changed)
        assertEquals("fr", changed?.language)
        assertEquals(otherUrl, changed?.serverUrl)
    }

    @Test
    fun `a value that is not exactly what a switch sends is refused`() {
        for (key in listOf("preloadModel", "formattingEnabled", "showTileOnKeyboard", "shakeWakeMode")) {
            for (value in listOf("True", "yes", "", "  ", "1", "0", "on", "off")) {
                assertNull("key $key value '$value'", applied(ScreenIntent.SetSetting(key, value)))
            }
        }
    }

    @Test
    fun `a key the screen offers no switch for is refused`() {
        for (key in listOf(
            "language",
            "modelSize",
            "serverUrl",
            "apiKeyRef",
            "tilePosition",
            "wakeGestureEnabled",
            "mode",
            "themeMode",
            "PreloadModel",
            "preloadmodel",
            "",
        )) {
            assertNull("key '$key'", applied(ScreenIntent.SetSetting(key, "true")))
        }
    }

    @Test
    fun `a routing mode is matched by its exact name only`() {
        // "AUTO" is an exact name and is applied, as the sibling test above asserts for every entry;
        // only a name in another case or no mode at all is refused here.
        for (name in listOf("local", "Local", "aUTO", "server", "", "NOPE", "AUTO, LOCAL")) {
            assertNull("routing mode '$name'", applied(ScreenIntent.SetRoutingMode(name)))
        }
    }

    @Test
    fun `each shake to wake mode name sets that mode and keeps every other field`() {
        for (mode in ShakeWakeMode.entries) {
            assertEquals(
                "mode ${mode.name}",
                NONDEFAULT.copy(shakeWakeMode = mode),
                applied(ScreenIntent.SetSetting("shakeWakeMode", mode.name)),
            )
        }
    }

    @Test
    fun `a shake to wake name that is not one of the three is refused`() {
        for (name in listOf("always", "Always", "MAYBE", "OFF ", "", "ALWAYS, OFF", "KEYBOARD_ONLYx")) {
            assertNull("shake to wake name '$name'", applied(ScreenIntent.SetSetting("shakeWakeMode", name)))
        }
    }

    @Test
    fun `the shake key refuses a boolean and the keyboard switch refuses a mode name`() {
        for (value in listOf("true", "false")) {
            assertNull("shake key with value $value", applied(ScreenIntent.SetSetting("shakeWakeMode", value)))
        }
        for (name in listOf("ALWAYS", "KEYBOARD_ONLY", "OFF")) {
            assertNull("keyboard switch with value $name", applied(ScreenIntent.SetSetting("showTileOnKeyboard", name)))
        }
    }

    @Test
    fun `the theme intents belong to the theme controller, not to this screen`() {
        assertNull(screen.editFor(ScreenIntent.ToggleTheme))
        assertNull(screen.editFor(ScreenIntent.UseSystemTheme))
        for (mode in listOf("SYSTEM", "LIGHT", "DARK")) {
            assertNull("theme mode $mode", screen.editFor(ScreenIntent.SetThemeMode(mode)))
        }
    }
}
