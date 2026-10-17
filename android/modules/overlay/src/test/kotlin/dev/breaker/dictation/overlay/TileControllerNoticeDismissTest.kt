package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A tap on the strip of a notice window clears the notice and collapses the tile, calling
 * [onNoticeDismissed] once and not [onTap].
 *
 * The screen is the one in [TestData]: the saved centre puts the square tile at (506, 1140), 100 pixels
 * square. The notice window is 300 by 150 pixels with its top-left corner at (406, 1090); the strip is
 * y 1090..1140, the microphone is x 506..606 and y 1140..1240.
 */
class TileControllerNoticeDismissTest {

    private val strip = 556f to 1110f
    private val mic = 556f to 1190f

    private class Rig(seed: AppSettings = AppSettings(), onNoticeDismissed: () -> Unit = {}) {
        val log = ArrayList<String>()
        val window = FakeTileWindow()
        val store = FakeSettingsStore(seed)
        val controller = TileController(
            window, store,
            onTap = { log.add("tap") },
            theme = ThemeMode.LIGHT,
            slopPx = TestData.SLOP_PX,
            onNoticeDismissed = { log.add("noticeDismissed"); onNoticeDismissed() },
        )

        /** Show the tile, push a notice and forget the calls made so far. */
        fun shownWithNotice(state: TileState = TileState.IDLE): Rig {
            assertEquals("overlay: show should report SHOWN", ShowResult.SHOWN, controller.show())
            controller.setState(state)
            controller.showNotice("Turn dictation on first")
            forget()
            return this
        }

        fun forget() {
            window.calls.clear()
            window.frames.clear()
            window.appliedFaces.clear()
            window.moves.clear()
            log.clear()
        }

        fun tap(p: Pair<Float, Float>) {
            window.down(p.first, p.second)
            window.up(p.first, p.second)
        }

        /** Tap [p] and return what the callbacks logged, clearing the log. */
        fun tapped(p: Pair<Float, Float>): List<String> {
            tap(p)
            return log.toList().also { log.clear() }
        }
    }

    /** If this fails, a strip tap does not clear the notice and collapse the tile. */
    @Test
    fun `a tap on the strip clears the notice and collapses the tile`() {
        val rig = Rig().shownWithNotice()
        rig.tap(strip)

        assertEquals("overlay: the strip tap should resize the window then draw", listOf("setFrame", "applyFace"), rig.window.calls)
        assertEquals(
            "overlay: the window should collapse to the square tile",
            listOf(FakeTileWindow.Frame(506, 1140, 100, 100)),
            rig.window.frames,
        )
        val face = rig.window.appliedFaces.single()
        assertEquals("overlay: the face should be the collapsed shape", TileShape.COLLAPSED, face.shape)
        assertNull("overlay: the face should carry no notice", face.notice)
        assertTrue("overlay: a strip tap should move nothing", rig.window.moves.isEmpty())
        assertEquals("overlay: a strip tap should save nothing", 0, rig.store.saveCount)
    }

    /** If this fails, onNoticeDismissed is called more than once or not at all for a strip tap. */
    @Test
    fun `onNoticeDismissed is called exactly once per strip tap`() {
        var count = 0
        val rig = Rig(onNoticeDismissed = { count++ }).shownWithNotice()
        rig.tap(strip)

        assertEquals("overlay: onNoticeDismissed should be called once", 1, count)
        assertEquals("overlay: the callback should log once", listOf("noticeDismissed"), rig.log)
    }

    /** If this fails, onTap is called for a strip tap, which would re-show the notice. */
    @Test
    fun `onTap is not called for a strip tap`() {
        val rig = Rig().shownWithNotice()
        rig.tap(strip)

        assertEquals("overlay: a strip tap should log only the dismiss", listOf("noticeDismissed"), rig.log)
        assertTrue("overlay: the tile should have no tap in its log", rig.log.none { it == "tap" })
    }

    /** If this fails, a null onNoticeDismissed crashes the strip tap instead of doing nothing. */
    @Test
    fun `a null onNoticeDismissed is safe and the tap still clears and collapses`() {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(AppSettings())
        val controller = TileController(
            window, store,
            onTap = {},
            theme = ThemeMode.LIGHT,
            slopPx = TestData.SLOP_PX,
        )
        assertEquals("overlay: show should report SHOWN", ShowResult.SHOWN, controller.show())
        controller.showNotice("Turn dictation on first")
        window.calls.clear()
        window.frames.clear()
        window.appliedFaces.clear()

        window.down(strip.first, strip.second)
        window.up(strip.first, strip.second)

        assertEquals("overlay: the strip tap should still resize and draw", listOf("setFrame", "applyFace"), window.calls)
        assertEquals(
            "overlay: the window should still collapse to the square tile",
            listOf(FakeTileWindow.Frame(506, 1140, 100, 100)),
            window.frames,
        )
        val face = window.appliedFaces.single()
        assertEquals("overlay: the face should be collapsed", TileShape.COLLAPSED, face.shape)
        assertNull("overlay: the face should carry no notice", face.notice)
    }

    /** If this fails, the microphone stops answering under a notice. */
    @Test
    fun `a tap on the microphone under a notice still calls onTap once`() {
        val rig = Rig().shownWithNotice()
        assertEquals("overlay: the microphone under a notice should call onTap once", listOf("tap"), rig.tapped(mic))
    }

    /** If this fails, a display change drops the notice or the strip tap no longer dismisses it. */
    @Test
    fun `a notice survives a display change and a later strip tap still dismisses it`() {
        val rig = Rig().shownWithNotice()
        // A new screen of 1200 by 2200 pixels with a tile of 200: the saved fraction (0.5, 0.5) puts the
        // collapsed tile at (500, 1000), so the wide window of 600 by 300 starts at (300, 900).
        rig.window.bounds = PixelBounds(0, 0, 1200, 2200)
        rig.window.sizePx = 200
        rig.controller.onDisplayChanged()

        // The notice window is placed again on the new screen; the face is not redrawn but the notice
        // is still in the model. A strip tap on the new screen should still dismiss it.
        rig.forget()
        rig.tap(556f to 950f)

        assertEquals("overlay: the strip tap after a display change should still resize and draw", listOf("setFrame", "applyFace"), rig.window.calls)
        assertEquals(
            "overlay: the window should still collapse to the square tile",
            listOf(FakeTileWindow.Frame(500, 1000, 200, 200)),
            rig.window.frames,
        )
        val collapsedFace = rig.window.appliedFaces.single()
        assertEquals("overlay: the face should be collapsed", TileShape.COLLAPSED, collapsedFace.shape)
        assertNull("overlay: the face should carry no notice", collapsedFace.notice)
        assertEquals("overlay: onNoticeDismissed should be called once", listOf("noticeDismissed"), rig.log)
    }
}
