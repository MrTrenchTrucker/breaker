package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.TilePosition
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Showing and hiding the tile, over a fake window and a fake settings store.
 *
 * The default screen is the one in [TestData]: origin (16, 80), movable range 980 by 2120.
 * Every expected pixel below is worked out by hand from that range.
 */
class TileControllerShowHideTest {

    /** If this fails, show no longer places the tile at the saved fraction of the movable range. */
    @Test
    fun `show adds one window at the saved position and reports SHOWN`() {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(TestData.settings(TilePosition(0.25f, 0.75f)))
        val controller = TestData.controller(window, store)

        val result = controller.show()

        assertSame("android_overlay: show should report SHOWN", ShowResult.SHOWN, result)
        assertEquals("android_overlay: expected exactly one window added", 1, window.adds.size)
        // x = 16 + 0.25 * 980 = 16 + 245; y = 80 + 0.75 * 2120 = 80 + 1590
        assertEquals("android_overlay: expected x 261 (16 + 245)", 261, window.adds[0].x)
        assertEquals("android_overlay: expected y 1670 (80 + 1590)", 1670, window.adds[0].y)
        assertSame("android_overlay: expected the light palette", TruckingTokens.LIGHT, window.adds[0].palette)
        assertTrue("android_overlay: expected the tile to be shown", controller.isShown)
        assertEquals("android_overlay: expected one settings read", 1, store.loadCount)
        assertEquals("android_overlay: show must not save", 0, store.saveCount)
        assertEquals("android_overlay: show must not remove anything", 0, window.removeCount)
    }

    /** If this fails, show touches the window or the settings without the overlay permission. */
    @Test
    fun `show without the overlay permission adds nothing and touches no settings`() {
        val window = FakeTileWindow(permission = false)
        val store = FakeSettingsStore(TestData.settings(TilePosition(0.25f, 0.75f)))
        val controller = TestData.controller(window, store)

        val result = controller.show()
        controller.hide()

        assertSame("android_overlay: expected PERMISSION_MISSING", ShowResult.PERMISSION_MISSING, result)
        assertEquals("android_overlay: expected no window added", 0, window.adds.size)
        assertEquals("android_overlay: expected no settings read", 0, store.loadCount)
        assertEquals("android_overlay: expected no settings save", 0, store.saveCount)
        assertFalse("android_overlay: expected the tile to stay hidden", controller.isShown)
        assertEquals("android_overlay: a hide after the refusal must not remove a window", 0, window.removeCount)
    }

    /** If this fails, a missing permission is remembered and the tile cannot appear once it is granted. */
    @Test
    fun `show works once the permission has been granted`() {
        val window = FakeTileWindow(permission = false)
        val store = FakeSettingsStore(AppSettings())
        val controller = TestData.controller(window, store)

        val first = controller.show()
        window.permission = true
        val second = controller.show()

        assertSame("android_overlay: expected PERMISSION_MISSING first", ShowResult.PERMISSION_MISSING, first)
        assertSame("android_overlay: expected SHOWN once the permission is granted", ShowResult.SHOWN, second)
        assertEquals("android_overlay: expected one window added in total", 1, window.adds.size)
        // default fraction (0.5, 0.5): x = 16 + 490, y = 80 + 1060
        assertEquals("android_overlay: expected x 506 (16 + 490)", 506, window.adds[0].x)
        assertEquals("android_overlay: expected y 1140 (80 + 1060)", 1140, window.adds[0].y)
        assertTrue("android_overlay: expected the tile to be shown", controller.isShown)
    }

    /** If this fails, a window the system refused is reported as shown, or the tile cannot recover. */
    @Test
    fun `a refused window reports FAILED and leaves the tile hidden`() {
        val window = FakeTileWindow(addOutcome = AddOutcome.REFUSED)
        val store = FakeSettingsStore(AppSettings())
        val controller = TestData.controller(window, store)

        val first = controller.show()

        assertSame("android_overlay: expected FAILED when the window refuses", ShowResult.FAILED, first)
        assertEquals("android_overlay: expected one attempt to add", 1, window.adds.size)
        assertFalse("android_overlay: expected the tile to stay hidden", controller.isShown)
        controller.hide()
        assertEquals("android_overlay: a refused window is not attached, so hide must not remove", 0, window.removeCount)

        window.addOutcome = AddOutcome.ADDED
        val second = controller.show()

        assertSame("android_overlay: expected SHOWN once the window accepts", ShowResult.SHOWN, second)
        assertEquals("android_overlay: expected a second attempt to add", 2, window.adds.size)
        assertTrue("android_overlay: expected the tile to be shown", controller.isShown)
    }

    /** If this fails, a repeated show adds a second window or reads the settings again. */
    @Test
    fun `a second show reports ALREADY_SHOWN and adds nothing`() {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(AppSettings())
        val controller = TestData.controller(window, store)

        val first = controller.show()
        val second = controller.show()

        assertSame("android_overlay: expected SHOWN first", ShowResult.SHOWN, first)
        assertSame("android_overlay: expected ALREADY_SHOWN second", ShowResult.ALREADY_SHOWN, second)
        assertEquals("android_overlay: expected exactly one window added", 1, window.adds.size)
        assertEquals("android_overlay: expected exactly one settings read", 1, store.loadCount)
        assertTrue("android_overlay: expected the tile to still be shown", controller.isShown)
    }

    /** If this fails, hide leaves the window attached or blocks a later show. */
    @Test
    fun `hide removes the window and the tile can be shown again`() {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(AppSettings())
        val controller = TestData.controller(window, store)
        controller.show()

        controller.hide()

        assertEquals("android_overlay: expected the window removed once", 1, window.removeCount)
        assertFalse("android_overlay: expected the tile to be hidden", controller.isShown)

        val again = controller.show()

        assertSame("android_overlay: expected SHOWN after a hide", ShowResult.SHOWN, again)
        assertEquals("android_overlay: expected a second window added", 2, window.adds.size)
        assertTrue("android_overlay: expected the tile to be shown again", controller.isShown)
        assertEquals("android_overlay: show must not remove", 1, window.removeCount)
    }

    /** If this fails, hide on a hidden tile calls the window or throws, or a second hide removes twice. */
    @Test
    fun `hide when hidden does nothing and does not fail`() {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(AppSettings())
        val controller = TestData.controller(window, store)

        controller.hide()

        assertEquals("android_overlay: a hide before any show must not remove", 0, window.removeCount)
        assertEquals("android_overlay: a hide before any show must not save", 0, store.saveCount)
        assertEquals("android_overlay: a hide before any show must not read settings", 0, store.loadCount)
        assertFalse("android_overlay: expected the tile to be hidden", controller.isShown)

        controller.show()
        controller.hide()
        controller.hide()

        assertEquals("android_overlay: two hides after one show remove the window exactly once", 1, window.removeCount)
        assertFalse("android_overlay: expected the tile to be hidden", controller.isShown)
        assertEquals("android_overlay: hiding without a drag must not save", 0, store.saveCount)
    }

    /** If this fails, show reuses an old position instead of reading the saved one again. */
    @Test
    fun `show after hide reads the position again`() {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(TestData.settings(TilePosition(0.25f, 0.75f)))
        val controller = TestData.controller(window, store)
        controller.show()
        controller.hide()

        store.current = TestData.settings(TilePosition(0.75f, 0.25f))
        controller.show()

        assertEquals("android_overlay: expected two settings reads", 2, store.loadCount)
        assertEquals("android_overlay: expected two windows added", 2, window.adds.size)
        assertEquals("android_overlay: expected the first x 261 (16 + 245)", 261, window.adds[0].x)
        assertEquals("android_overlay: expected the first y 1670 (80 + 1590)", 1670, window.adds[0].y)
        // second: x = 16 + 0.75 * 980 = 16 + 735; y = 80 + 0.25 * 2120 = 80 + 530
        assertEquals("android_overlay: expected the second x 751 (16 + 735)", 751, window.adds[1].x)
        assertEquals("android_overlay: expected the second y 610 (80 + 530)", 610, window.adds[1].y)
    }

    /** If this fails, a failing settings read stops the tile appearing or puts it somewhere other than the centre. */
    @Test
    fun `a settings read that fails puts the tile in the centre`() {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(TestData.settings(TilePosition(0.25f, 0.75f)))
        store.failLoad = IllegalStateException("android_overlay: simulated read failure")
        val controller = TestData.controller(window, store)

        val result = controller.show()

        assertSame("android_overlay: expected SHOWN despite the failed read", ShowResult.SHOWN, result)
        assertEquals("android_overlay: expected one window added", 1, window.adds.size)
        assertEquals("android_overlay: expected the centre x 506 (16 + 490)", 506, window.adds[0].x)
        assertEquals("android_overlay: expected the centre y 1140 (80 + 1060)", 1140, window.adds[0].y)
        assertEquals("android_overlay: expected one attempted read", 1, store.loadCount)
        assertEquals("android_overlay: a failed read must not save", 0, store.saveCount)
        assertTrue("android_overlay: expected the tile to be shown", controller.isShown)
    }

    /** If this fails, hiding in the middle of a drag loses the dragged position or leaves the window attached. */
    @Test
    fun `hide in the middle of a drag saves the dragged position first`() {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(TestData.settings(TilePosition(0.5f, 0.5f)))
        var taps = 0
        val controller = TestData.controller(window, store, onTap = { taps++ })
        controller.show()

        // The tile is at (506, 1140). The finger goes down and moves by (-245, +530),
        // far past the slop of 8, so the tile moves to (261, 1670): fraction
        // (261 - 16) / 980 = 0.25 and (1670 - 80) / 2120 = 0.75.
        window.down(600f, 1200f)
        window.move(355f, 1730f)
        assertEquals("android_overlay: expected one move while dragging", listOf(PixelPoint(261, 1670)), window.moves)
        assertEquals("android_overlay: nothing may be saved while the finger is down", 0, store.saveCount)

        controller.hide()

        assertEquals("android_overlay: expected exactly one save from the hide", 1, store.saveCount)
        assertEquals("android_overlay: expected one saved settings value", 1, store.saved.size)
        assertEquals("android_overlay: expected saved x 0.25", 0.25f, store.saved[0].tilePosition.x, 1e-6f)
        assertEquals("android_overlay: expected saved y 0.75", 0.75f, store.saved[0].tilePosition.y, 1e-6f)
        assertEquals("android_overlay: expected the window removed once", 1, window.removeCount)
        assertFalse("android_overlay: expected the tile to be hidden", controller.isShown)
        assertEquals("android_overlay: a drag must never tap", 0, taps)

        controller.show()

        assertEquals("android_overlay: expected a second window added", 2, window.adds.size)
        assertEquals("android_overlay: expected the next show at x 261", 261, window.adds[1].x)
        assertEquals("android_overlay: expected the next show at y 1670", 1670, window.adds[1].y)
    }

    /** A failure means hide leaves a pressed finger in the gesture, so a release after the next show is taken for a tap. */
    @Test
    fun `hide drops a pressed finger that was not a drag`() {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(TestData.settings(TilePosition(0.5f, 0.5f)))
        var taps = 0
        val controller = TestData.controller(window, store, onTap = { taps++ })
        controller.show()
        assertEquals("android_overlay: expected the tile at x 506 (16 + 490)", 506, window.adds[0].x)
        assertEquals("android_overlay: expected the tile at y 1140 (80 + 1060)", 1140, window.adds[0].y)

        // The finger goes down on the tile and does not move: a press that is not a drag.
        window.down(600f, 1200f)
        controller.hide()
        controller.show()
        window.up(600f, 1200f)

        assertEquals("android_overlay: a release after hide and show must not tap", 0, taps)
        assertEquals("android_overlay: a release that is not a tap must not save", 0, store.saveCount)
        assertEquals("android_overlay: expected the window removed once", 1, window.removeCount)
        assertEquals("android_overlay: expected two windows added in total", 2, window.adds.size)
        assertTrue("android_overlay: expected the tile to be shown again", controller.isShown)
    }

    /** A failure means show asks for the overlay permission before it checks whether the tile is already shown. */
    @Test
    fun `a second show reports ALREADY_SHOWN even after the permission was taken away`() {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(AppSettings())
        val controller = TestData.controller(window, store)

        val first = controller.show()
        window.permission = false
        val second = controller.show()

        assertSame("android_overlay: expected SHOWN first", ShowResult.SHOWN, first)
        assertSame("android_overlay: expected ALREADY_SHOWN, not PERMISSION_MISSING", ShowResult.ALREADY_SHOWN, second)
        assertEquals("android_overlay: expected the permission asked exactly once, by the first show", 1, window.canDrawCalls)
        assertEquals("android_overlay: expected exactly one window added", 1, window.adds.size)
        assertEquals("android_overlay: expected exactly one settings read", 1, store.loadCount)
        assertEquals("android_overlay: expected no window removed", 0, window.removeCount)
        assertTrue("android_overlay: expected the tile to still be shown", controller.isShown)
    }
}
