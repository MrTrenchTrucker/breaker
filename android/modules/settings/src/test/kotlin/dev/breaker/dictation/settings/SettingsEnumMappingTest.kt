package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.ThemeMode
import dev.breaker.dictation.core.model.WakeGestureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test
import java.util.Properties
import java.io.File

/**
 * Enum mapping both ways, and the read-side tolerance a hand-edited word gets.
 *
 * The file exists to survive hand edits, so a name typed in another case is that
 * name; the tolerance is READ-side only — saving what was read writes the
 * canonical name back, so a lower-case value never spreads through the file.
 */
class SettingsEnumMappingTest : SettingsFileStoreTestBase() {

    @Test
    fun `every theme name maps from the file and back to the file`() {
        for (theme in ThemeMode.entries) {
            val file = newFile()
            store(file).save(validSettings().copy(themeMode = theme))
            assertEquals(theme, ThemeMode.valueOf(propertyOf(file, KEY_THEME_MODE)))

            val reloaded = store(file).load()
            assertEquals(theme, reloaded.themeMode)
        }
    }

    @Test
    fun `every mode name maps from the file and back to the file`() {
        for (mode in SttMode.entries) {
            val file = newFile()
            store(file).save(validSettings().copy(mode = mode))
            assertEquals(mode, SttMode.valueOf(propertyOf(file, KEY_MODE)))

            val reloaded = store(file).load()
            assertEquals(mode, reloaded.mode)
        }
    }

    @Test
    fun `an unknown enum name falls back rather than throwing`() {
        val unknownTheme = try {
            store(fileWith(KEY_THEME_MODE, "NEON")).load().themeMode
        } catch (thrown: RuntimeException) {
            fail("an unknown theme name crashed the store instead of falling back: $thrown")
            throw AssertionError(thrown)
        }
        val unknownMode = try {
            store(fileWith(KEY_MODE, "SATELLITE")).load().mode
        } catch (thrown: RuntimeException) {
            fail("an unknown mode name crashed the store instead of falling back: $thrown")
            throw AssertionError(thrown)
        }

        assertEquals(AppSettings().themeMode, unknownTheme)
        assertEquals(AppSettings().mode, unknownMode)
    }

    // --- a name in the wrong case is still that name ---

    /**
     * A lower-case theme name is read as that theme, not as a bad value.
     *
     * A hand editor types `theme_mode=dark` without thinking about the case.
     * Falling back to the default would be a silent theme change with nothing to
     * indicate it, so the case-insensitive match is pinned here with the exact
     * value it must produce — asserting merely "not the default" would also pass
     * against a store that mapped every unrecognised name to some other theme.
     */
    @Test
    fun `a lower-case theme name in the file is read as that theme`() {
        val file = fileWith(KEY_THEME_MODE, "dark")

        val loaded = store(file).load()

        assertEquals(ThemeMode.DARK, loaded.themeMode)

        store(file).save(loaded)
        assertEquals(ThemeMode.DARK.name, propertyOf(file, KEY_THEME_MODE))
    }

    /** The same tolerance for the mode enum, pinned on [SttMode.SERVER]. */
    @Test
    fun `a lower-case mode name in the file is read as that mode`() {
        val loaded = store(fileWith(KEY_MODE, "server")).load()

        assertEquals(SttMode.SERVER, loaded.mode)
    }

    // --- a hand-edited word is that word, whatever case it was typed in ---

    /** Legacy wake_gesture_enabled key: true=ALWAYS, false=OFF, missing=ALWAYS, new key wins if both present. */

    /** Builds a valid file with only [wakeKey] set to [wakeValue]; no other wake-gesture key. */
    private fun legacyFile(wakeKey: String, wakeValue: String): File {
        val props = Properties()
        props.setProperty(KEY_MODE, SttMode.SERVER.name)
        props.setProperty(KEY_MODEL_SIZE, "medium")
        props.setProperty(KEY_SERVER_URL, "https://box.local")
        props.setProperty(KEY_TILE_POSITION, "0.25,0.75")
        props.setProperty(KEY_LANGUAGE, "de")
        props.setProperty(KEY_PRELOAD_MODEL, "false")
        props.setProperty(KEY_FORMATTING, "false")
        props.setProperty(KEY_THEME_MODE, ThemeMode.DARK.name)
        props.setProperty(wakeKey, wakeValue)
        val file = newFile()
        file.outputStream().use { props.store(it, "test") }
        return file
    }

    @Test
    fun `legacy key true maps to ALWAYS`() {
        val loaded = store(legacyFile(KEY_WAKE_GESTURE_ENABLED, "true")).load()
        assertEquals("legacy wake_gesture_enabled=true alone", WakeGestureMode.ALWAYS, loaded.wakeGestureMode)
    }

    @Test
    fun `legacy key false maps to OFF`() {
        val loaded = store(legacyFile(KEY_WAKE_GESTURE_ENABLED, "false")).load()
        assertEquals("legacy wake_gesture_enabled=false alone", WakeGestureMode.OFF, loaded.wakeGestureMode)
    }

    @Test
    fun `legacy key missing maps to ALWAYS`() {
        val loaded = store(legacyFile(KEY_WAKE_GESTURE_MODE, "")).load()
        assertEquals("neither wake gesture key present", WakeGestureMode.ALWAYS, loaded.wakeGestureMode)
    }

    @Test
    fun `new key wins when both keys present`() {
        val props = Properties()
        props.setProperty(KEY_MODE, SttMode.SERVER.name)
        props.setProperty(KEY_MODEL_SIZE, "medium")
        props.setProperty(KEY_SERVER_URL, "https://box.local")
        props.setProperty(KEY_WAKE_GESTURE_MODE, "OFF")
        props.setProperty(KEY_WAKE_GESTURE_ENABLED, "true")
        props.setProperty(KEY_TILE_POSITION, "0.25,0.75")
        props.setProperty(KEY_LANGUAGE, "de")
        props.setProperty(KEY_PRELOAD_MODEL, "false")
        props.setProperty(KEY_FORMATTING, "false")
        props.setProperty(KEY_THEME_MODE, ThemeMode.DARK.name)
        val file = newFile()
        file.outputStream().use { props.store(it, "test") }
        val loaded = store(file).load()
        assertEquals("new key OFF overrides legacy true", WakeGestureMode.OFF, loaded.wakeGestureMode)
    }

    /**
     * `FALSE` in the file turns the setting off. The booleans default to true,
     * so a hand editor who writes `FALSE` and gets `true` is given the opposite
     * of what they asked for. Enum names were already read case-insensitively;
     * a boolean is the same kind of word. All four spellings are pinned
     * together, so the tolerance cannot become "anything not false is true".
     */
    @Test
    fun `a boolean in the file is read whatever case it was written in`() {
        for ((written, expected) in listOf("ALWAYS" to WakeGestureMode.ALWAYS, "KEYBOARD_ONLY" to WakeGestureMode.KEYBOARD_ONLY, "OFF" to WakeGestureMode.OFF, "maybe" to WakeGestureMode.ALWAYS)) {
            assertEquals(
                "wake_gesture_mode written as $written",
                expected,
                store(fileWith(KEY_WAKE_GESTURE_MODE, written)).load().wakeGestureMode,
            )
        }
    }

    /**
     * A boolean with whitespace around it is that boolean. The format keeps
     * trailing whitespace in a value, so padding `false` arrives intact and
     * reading it as unrecognised keeps the default, switching the setting back
     * on silently. Read back raw first, so a format that dropped the
     * whitespace fails as a premise rather than quietly agreeing.
     */
    @Test
    fun `a boolean with whitespace around it is read as that boolean`() {
        val planted = "off"
        val file = fileWith(KEY_WAKE_GESTURE_MODE, planted)
        assertEquals("the planted whitespace did not survive the file", planted, propertyOf(file, KEY_WAKE_GESTURE_MODE))

        val loaded = store(file).load()

        assertEquals("a padded off", WakeGestureMode.OFF, loaded.wakeGestureMode)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_WAKE_GESTURE_MODE)
    }

    /**
     * Trailing whitespace is not part of a string value: a URL, a model size
     * and a language tag have no meaningful trailing space, and the format
     * hands one back untouched, so a padded address reached the app as an
     * address that cannot resolve. Read back raw first: the premise is proved,
     * not assumed.
     */
    @Test
    fun `a string value keeps no trailing whitespace`() {
        val planted = "https://box.local   "
        val file = fileWith(KEY_SERVER_URL, planted)
        assertEquals("the planted whitespace did not survive the file", planted, propertyOf(file, KEY_SERVER_URL))

        val loaded = store(file).load()

        assertEquals("server address", "https://box.local", loaded.serverUrl)
        assertEquals("a padded address is a configured address", true, loaded.hasServerUrl)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_SERVER_URL)
    }
}
