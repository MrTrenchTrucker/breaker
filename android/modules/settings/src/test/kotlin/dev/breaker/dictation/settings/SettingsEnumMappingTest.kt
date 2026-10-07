package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.SttMode
import dev.breaker.dictation.core.model.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

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

    /**
     * `FALSE` in the file turns the setting off. The booleans default to true,
     * so a hand editor who writes `FALSE` and gets `true` is given the opposite
     * of what they asked for. Enum names were already read case-insensitively;
     * a boolean is the same kind of word. All four spellings are pinned
     * together, so the tolerance cannot become "anything not false is true".
     */
    @Test
    fun `a boolean in the file is read whatever case it was written in`() {
        for ((written, expected) in listOf("FALSE" to false, "false" to false, "True" to true, "true" to true)) {
            assertEquals(
                "wake_gesture_enabled written as $written",
                expected,
                store(fileWith(KEY_WAKE_GESTURE, written)).load().wakeGestureEnabled,
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
        val planted = "false   "
        val file = fileWith(KEY_WAKE_GESTURE, planted)
        assertEquals("the planted whitespace did not survive the file", planted, propertyOf(file, KEY_WAKE_GESTURE))

        val loaded = store(file).load()

        assertEquals("a padded false", false, loaded.wakeGestureEnabled)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_WAKE_GESTURE)
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
