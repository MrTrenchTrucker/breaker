package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.TilePosition
import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public [FloatingTile], built with its internal constructor over a controller on fakes.
 *
 * Each test builds its own fakes. The screen is the one in [TestData].
 */
class FloatingTileFacadeTest {

    /** If this fails, the facade changes or invents a result instead of returning the controller's. */
    @Test
    fun `show returns what the controller returns`() {
        val shownWindow = FakeTileWindow()
        val shown = FloatingTile(TestData.controller(shownWindow, FakeSettingsStore(AppSettings())))
        val first = shown.show()
        val second = shown.show()

        val missingWindow = FakeTileWindow(permission = false)
        val missing = FloatingTile(TestData.controller(missingWindow, FakeSettingsStore(AppSettings())))
        val third = missing.show()

        val refusedWindow = FakeTileWindow(addOutcome = AddOutcome.REFUSED)
        val refused = FloatingTile(TestData.controller(refusedWindow, FakeSettingsStore(AppSettings())))
        val fourth = refused.show()

        assertSame("android_overlay: expected SHOWN from the first show", ShowResult.SHOWN, first)
        assertSame("android_overlay: expected ALREADY_SHOWN from the second show", ShowResult.ALREADY_SHOWN, second)
        assertSame("android_overlay: expected PERMISSION_MISSING without the permission", ShowResult.PERMISSION_MISSING, third)
        assertSame("android_overlay: expected FAILED from a refused window", ShowResult.FAILED, fourth)
        assertEquals("android_overlay: expected the shown tile to add one window", 1, shownWindow.adds.size)
        assertEquals("android_overlay: expected no add without the permission", 0, missingWindow.adds.size)
        assertEquals("android_overlay: expected one attempted add on the refused tile", 1, refusedWindow.adds.size)
    }

    /** If this fails, hide on the facade never reaches the controller. */
    @Test
    fun `hide reaches the controller`() {
        val window = FakeTileWindow()
        val tile = FloatingTile(TestData.controller(window, FakeSettingsStore(AppSettings())))
        tile.show()

        tile.hide()

        assertEquals("android_overlay: expected the window removed once", 1, window.removeCount)
        assertFalse("android_overlay: expected the tile to be hidden", tile.isShown)
    }

    /** If this fails, setTheme or onDisplayChanged on the facade does not reach the controller. */
    @Test
    fun `setTheme and onDisplayChanged reach the controller`() {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(TestData.settings(TilePosition(0.25f, 0.75f)))
        val tile = FloatingTile(TestData.controller(window, store, theme = ThemeMode.LIGHT))
        tile.show()

        tile.setTheme(ThemeMode.DARK)

        assertEquals("android_overlay: expected one palette applied", 1, window.appliedPalettes.size)
        assertSame("android_overlay: expected the dark palette applied", TruckingTokens.DARK, window.appliedPalettes[0])

        // Fresh screen: origin (0, 0), 1200 by 2200 pixels, tile 200 pixels square, so the movable
        // range is 1000 by 2000 and the saved fraction (0.25, 0.75) is x 250, y 1500.
        window.bounds = PixelBounds(0, 0, 1200, 2200)
        window.sizePx = 200
        tile.onDisplayChanged()

        assertEquals("android_overlay: expected one move after the display change", listOf(PixelPoint(250, 1500)), window.moves)
    }

    /** If this fails, the facade keeps its own shown flag instead of following the controller. */
    @Test
    fun `isShown follows the controller`() {
        val window = FakeTileWindow(addOutcome = AddOutcome.REFUSED)
        val controller = TestData.controller(window, FakeSettingsStore(AppSettings()))
        val tile = FloatingTile(controller)
        assertFalse("android_overlay: expected hidden before any show", tile.isShown)
        assertEquals("android_overlay: expected the facade to match the controller at the start", controller.isShown, tile.isShown)

        tile.show()
        assertFalse("android_overlay: expected hidden after a refused show", tile.isShown)
        assertEquals("android_overlay: expected the facade to match the controller after a refusal", controller.isShown, tile.isShown)

        window.addOutcome = AddOutcome.ADDED
        tile.show()
        assertTrue("android_overlay: expected shown after an accepted show", tile.isShown)
        assertEquals("android_overlay: expected the facade to match the controller when shown", controller.isShown, tile.isShown)

        tile.hide()
        assertFalse("android_overlay: expected hidden after hide", tile.isShown)
        assertEquals("android_overlay: expected the facade to match the controller after hide", controller.isShown, tile.isShown)
    }
}
