package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.ThemeMode
import dev.breaker.dictation.core.port.SettingsStore
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.IOException

/**
 * Tests for [themeModeOrDefault], the launch-path guard that keeps a settings-load IOException from
 * crashing the activity: a store whose `load` throws answers the default theme, and a store that
 * reads answers its stored theme (so the guard does not merely always default).
 */
internal class ThemeModeOrDefaultTest {

    /** A store whose read fails the way an unreadable settings file does. */
    private class ThrowingStore : SettingsStore {
        override fun load(): AppSettings = throw IOException("unreadable settings file")
        override fun save(settings: AppSettings): Unit = Unit
    }

    /** A store that reads cleanly and returns the given theme. */
    private class StoringStore(private val mode: ThemeMode) : SettingsStore {
        override fun load(): AppSettings = AppSettings(themeMode = mode)
        override fun save(settings: AppSettings): Unit = Unit
    }

    @Test
    fun `an unreadable store answers the default theme instead of throwing`() {
        assertEquals(
            "a settings-load IOException must fall back to the default theme, not crash the caller",
            ThemeMode.SYSTEM,
            themeModeOrDefault(ThrowingStore(), ThemeMode.SYSTEM),
        )
    }

    @Test
    fun `a readable store answers its stored theme, not the default`() {
        assertEquals(
            "the guard must return the stored theme when the read succeeds, not always default",
            ThemeMode.LIGHT,
            themeModeOrDefault(StoringStore(ThemeMode.LIGHT), ThemeMode.SYSTEM),
        )
    }
}
