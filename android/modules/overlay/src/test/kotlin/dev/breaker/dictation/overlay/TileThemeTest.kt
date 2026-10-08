package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Which palette the window gets, and when.
 *
 * Palettes are compared by identity with the published light and dark tokens, so no colour is
 * worked out in the tests.
 */
class TileThemeTest {

    /** If this fails, show ignores the theme the tile was built with. */
    @Test
    fun `the theme given at construction is used by show`() {
        val darkWindow = FakeTileWindow()
        TestData.controller(darkWindow, FakeSettingsStore(AppSettings()), theme = ThemeMode.DARK).show()
        val lightWindow = FakeTileWindow()
        TestData.controller(lightWindow, FakeSettingsStore(AppSettings()), theme = ThemeMode.LIGHT).show()

        assertEquals("android_overlay: expected one add for the dark tile", 1, darkWindow.adds.size)
        assertSame("android_overlay: expected the dark palette", TruckingTokens.DARK, darkWindow.adds[0].palette)
        assertEquals("android_overlay: expected one add for the light tile", 1, lightWindow.adds.size)
        assertSame("android_overlay: expected the light palette", TruckingTokens.LIGHT, lightWindow.adds[0].palette)
    }

    /** If this fails, a theme set while hidden is lost and show uses the old one. */
    @Test
    fun `a theme set before show is used by show`() {
        val window = FakeTileWindow()
        val controller = TestData.controller(window, FakeSettingsStore(AppSettings()), theme = ThemeMode.LIGHT)

        controller.setTheme(ThemeMode.DARK)
        controller.show()

        assertEquals("android_overlay: expected one add", 1, window.adds.size)
        assertSame("android_overlay: expected the dark palette", TruckingTokens.DARK, window.adds[0].palette)
        assertEquals("android_overlay: expected no palette applied to a hidden tile", 0, window.appliedPalettes.size)
    }

    /** If this fails, a theme change while shown is not applied, or is applied more or less than once. */
    @Test
    fun `a theme set while shown is applied once`() {
        val window = FakeTileWindow()
        val controller = TestData.controller(window, FakeSettingsStore(AppSettings()), theme = ThemeMode.LIGHT)
        controller.show()

        controller.setTheme(ThemeMode.DARK)

        assertEquals("android_overlay: expected exactly one palette applied", 1, window.appliedPalettes.size)
        assertSame("android_overlay: expected the dark palette applied", TruckingTokens.DARK, window.appliedPalettes[0])
        assertEquals("android_overlay: expected the window added only once", 1, window.adds.size)
        assertSame("android_overlay: expected the first add to keep the light palette", TruckingTokens.LIGHT, window.adds[0].palette)
        assertEquals("android_overlay: a theme change must not remove the window", 0, window.removeCount)
    }

    /** If this fails, a theme set on a hidden tile calls into the window. */
    @Test
    fun `a theme set while hidden touches no window`() {
        val window = FakeTileWindow()
        val controller = TestData.controller(window, FakeSettingsStore(AppSettings()), theme = ThemeMode.LIGHT)

        controller.setTheme(ThemeMode.DARK)

        assertEquals("android_overlay: expected no palette applied", 0, window.appliedPalettes.size)
        assertEquals("android_overlay: expected no window added", 0, window.adds.size)
        assertEquals("android_overlay: expected no move", 0, window.moves.size)
        assertEquals("android_overlay: expected no remove", 0, window.removeCount)
        assertEquals("android_overlay: expected no permission check", 0, window.canDrawCalls)
        assertEquals("android_overlay: expected no bounds read", 0, window.boundsReads)
    }
}
