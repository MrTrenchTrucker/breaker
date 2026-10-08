package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.TilePosition
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The sentence the app can ask the tile to show: the window grows around the square tile, stays on the
 * screen, leaves the saved position alone, and goes back when the app clears it.
 *
 * The screen is the one in [TestData]: the saved centre puts the square tile at (506, 1140), 100 pixels
 * square, so the wide window of 300 by 150 pixels has its top-left corner at (406, 1090).
 */
class TileControllerNoticeTest {

    private class Rig(seed: AppSettings = AppSettings(), onTap: () -> Unit = {}) {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(seed)
        val controller = TileController(window, store, onTap = onTap, theme = ThemeMode.LIGHT, slopPx = TestData.SLOP_PX)

        /** Show the tile and forget the calls made so far, so a test reads only what its own step did. */
        fun shown(): Rig {
            assertEquals("overlay: show should report SHOWN", ShowResult.SHOWN, controller.show())
            forget()
            return this
        }

        fun forget() {
            window.calls.clear()
            window.frames.clear()
            window.appliedFaces.clear()
            window.moves.clear()
        }
    }

    private val collapsedFrame = FakeTileWindow.Frame(506, 1140, 100, 100)
    private val wideFrame = FakeTileWindow.Frame(406, 1090, 300, 150)

    /** If this fails, a notice does not widen the window around the square tile, or moves or saves the tile while doing it. */
    @Test
    fun `a notice widens the window and leaves the saved position alone`() {
        val rig = Rig().shown()
        rig.controller.showNotice("Turn dictation on first")

        assertEquals("overlay: a notice should size the window then draw", listOf("setFrame", "applyFace"), rig.window.calls)
        assertEquals("overlay: the notice window should surround the square tile", listOf(wideFrame), rig.window.frames)
        val face = rig.window.appliedFaces.single()
        assertEquals("overlay: the face should have the notice shape", TileShape.NOTICE, face.shape)
        assertEquals("overlay: the face should carry the sentence", "Turn dictation on first", face.notice)
        assertTrue("overlay: a notice should not move the square tile", rig.window.moves.isEmpty())
        assertEquals("overlay: a notice should not save anything", 0, rig.store.saveCount)
    }

    /** If this fails, a notice replaces the recording tile, or is stored while recording and shows up in the next face that is drawn. */
    @Test
    fun `a notice is ignored while recording`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.RECORDING)
        rig.forget()

        rig.controller.showNotice("Turn dictation on first")
        assertEquals("overlay: a notice while recording should call no window method", emptyList<String>(), rig.window.calls)

        // A push that keeps the recording state redraws the face; the face must not carry the ignored sentence.
        rig.controller.setLevel(0.5f)
        assertNull("overlay: the next face of a recording tile should carry no notice", rig.window.appliedFaces.last().notice)
        assertEquals("overlay: the next face should still be the recording shape", TileShape.RECORDING, rig.window.appliedFaces.last().shape)
    }

    /** If this fails, the app cannot take a sentence away, or taking it away twice redraws the tile. */
    @Test
    fun `clearing the notice shrinks the window and clearing again does nothing`() {
        val rig = Rig().shown()
        rig.controller.showNotice("Turn dictation on first")
        rig.forget()

        rig.controller.clearNotice()
        assertEquals("overlay: clearing should size the window then draw", listOf("setFrame", "applyFace"), rig.window.calls)
        assertEquals("overlay: the window should be the square tile again", listOf(collapsedFrame), rig.window.frames)
        assertNull("overlay: the face should have no notice", rig.window.appliedFaces.single().notice)
        assertEquals("overlay: the face should be square", TileShape.COLLAPSED, rig.window.appliedFaces.single().shape)

        rig.controller.clearNotice()
        assertEquals("overlay: clearing with no notice should call nothing more", listOf("setFrame", "applyFace"), rig.window.calls)
    }

    /** If this fails, the sentence reaches the window as the app wrote it, or a blank one still widens the tile. */
    @Test
    fun `the sentence is cleaned before it reaches the face`() {
        val rig = Rig().shown()

        rig.controller.showNotice("a".repeat(100))
        assertEquals("overlay: a long sentence should be cut to 80 characters", "a".repeat(80), rig.window.appliedFaces.last().notice)

        rig.controller.showNotice("  first line\nsecond\tpart  ")
        assertEquals("overlay: line breaks and tabs should become spaces and the ends be trimmed", "first line second part", rig.window.appliedFaces.last().notice)

        rig.controller.clearNotice()
        rig.forget()
        rig.controller.showNotice("   \n\t ")
        assertEquals("overlay: a blank sentence should call nothing", emptyList<String>(), rig.window.calls)
        assertEquals("overlay: a blank sentence should leave the tile square", 0, rig.window.frames.size)
    }

    /** If this fails, the tile resizes for a new sentence on a window that already has the size, or redraws for the same one. */
    @Test
    fun `a second sentence redraws without resizing and the same sentence draws nothing`() {
        val rig = Rig().shown()
        rig.controller.showNotice("Turn dictation on first")
        rig.forget()

        rig.controller.showNotice("Turn dictation on first")
        assertEquals("overlay: the same sentence should call nothing", emptyList<String>(), rig.window.calls)

        rig.controller.showNotice("Wait a moment")
        assertEquals("overlay: a new sentence should only draw", listOf("applyFace"), rig.window.calls)
        assertEquals("overlay: the new sentence should be in the face", "Wait a moment", rig.window.appliedFaces.single().notice)
        assertTrue("overlay: a new sentence should not resize the window", rig.window.frames.isEmpty())
    }

    /** If this fails, a window near the edge runs off the screen, or the edge changes where the square tile is saved. */
    @Test
    fun `the notice window is kept on screen and the square tile does not move`() {
        // Saved position (1.0, 0.0): the square tile is at x 996 (right edge of the range) and y 80 (top).
        val rig = Rig(TestData.settings(TilePosition(1.0f, 0.0f))).shown()
        rig.controller.showNotice("Turn dictation on first")

        assertEquals(
            "overlay: the window should be pulled in from the right edge and stay at the top edge",
            listOf(FakeTileWindow.Frame(796, 80, 300, 150)),
            rig.window.frames,
        )
        assertEquals("overlay: a notice at the edge should save nothing", 0, rig.store.saveCount)

        rig.controller.clearNotice()
        assertEquals("overlay: the square tile should be back where it was", collapsedFrame.copy(x = 996, y = 80), rig.window.frames.last())

        // A drag now starts from the square tile's own place, not from the pulled-in window.
        rig.forget()
        rig.window.down(300f, 300f)
        rig.window.move(270f, 340f)
        rig.window.up(270f, 340f)
        assertEquals("overlay: the drag should start from the square tile at (996, 80)", listOf(PixelPoint(966, 120)), rig.window.moves)
    }

    /** If this fails, a display change leaves a wide window where the old screen put it. */
    @Test
    fun `a display change places the notice window again around the square tile`() {
        val rig = Rig(TestData.settings(TilePosition(0.25f, 0.75f))).shown()
        rig.controller.showNotice("Turn dictation on first")
        rig.forget()

        // A new screen of 1200 by 2200 pixels with a tile of 200: the saved fraction is (250, 1500), so
        // the window of 600 by 300 pixels starts one tile left and half a tile up, at (50, 1400).
        rig.window.bounds = PixelBounds(0, 0, 1200, 2200)
        rig.window.sizePx = 200
        rig.controller.onDisplayChanged()

        assertEquals("overlay: a display change should resize and move the wide window once", listOf(FakeTileWindow.Frame(50, 1400, 600, 300)), rig.window.frames)
        assertTrue("overlay: a display change should not move the window as a square tile", rig.window.moves.isEmpty())
        assertTrue("overlay: a display change should not redraw the face", rig.window.appliedFaces.isEmpty())
        assertEquals("overlay: a display change should not save", 0, rig.store.saveCount)
    }

    /** If this fails, an app that answers a tap with a sentence from inside its callback gets no window change. */
    @Test
    fun `a notice pushed from inside the tap callback is drawn`() {
        var holder: TileController? = null
        val rig = Rig(onTap = { holder?.showNotice("Turn dictation on first") })
        holder = rig.controller
        rig.shown()

        rig.window.down(556f, 1190f)
        rig.window.up(556f, 1190f)

        assertEquals("overlay: the sentence pushed in the callback should size the window then draw", listOf("setFrame", "applyFace"), rig.window.calls)
        assertEquals("overlay: the window should be wide", listOf(wideFrame), rig.window.frames)
        assertEquals("overlay: the tap should save nothing", 0, rig.store.saveCount)
    }
}
