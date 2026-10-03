package dev.breaker.dictation.settings

import dev.breaker.dictation.core.model.AppSettings
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The file-backed keys fall back one at a time: a value core would reject, or
 * one the format cannot parse, costs that key alone.
 *
 * Each test carries [SettingsFileStoreTestBase.assertEveryOtherKeySurvived]
 * alongside its own assertion, because a store that discarded the whole file on
 * one bad value would satisfy a lone "this key got its default" check.
 */
class SettingsFallbackIsolationTest : SettingsFileStoreTestBase() {

    @Test
    fun `an unparseable boolean falls back alone`() {
        val loaded = store(fileWith(KEY_WAKE_GESTURE, "maybe")).load()

        assertEquals("wake_gesture_enabled", AppSettings().wakeGestureEnabled, loaded.wakeGestureEnabled)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_WAKE_GESTURE)
    }

    @Test
    fun `an unparseable tile falls back alone`() {
        val loaded = store(fileWith(KEY_TILE_POSITION, "abc")).load()

        assertEquals("tile_position", AppSettings().tilePosition, loaded.tilePosition)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_TILE_POSITION)
    }

    @Test
    fun `a blank language the core would reject falls back alone`() {
        val loaded = store(fileWith(KEY_LANGUAGE, "")).load()

        assertEquals("language", AppSettings().language, loaded.language)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_LANGUAGE)
    }

    @Test
    fun `a tile fraction outside zero to one the core would reject falls back alone`() {
        val loaded = store(fileWith(KEY_TILE_POSITION, "1.5,0.25")).load()

        assertEquals("tile_position", AppSettings().tilePosition, loaded.tilePosition)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_TILE_POSITION)
    }

    @Test
    fun `an unknown theme name falls back alone`() {
        val loaded = store(fileWith(KEY_THEME_MODE, "NEON")).load()

        assertEquals("theme_mode", AppSettings().themeMode, loaded.themeMode)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_THEME_MODE)
    }

    @Test
    fun `an unknown mode name falls back alone`() {
        val loaded = store(fileWith(KEY_MODE, "SATELLITE")).load()

        assertEquals("mode", AppSettings().mode, loaded.mode)
        assertEveryOtherKeySurvived(loaded, exceptKey = KEY_MODE)
    }
}
