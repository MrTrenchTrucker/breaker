package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the app pushed stays when the tile is taken off screen, and the next show draws it again.
 *
 * The screen is the one in [TestData]: the saved centre puts the square tile at (506, 1140), 100 pixels
 * square, so the wide window of 300 by 150 pixels has its top-left corner at (406, 1090).
 */
class TileControllerHideTest {

    private class Rig {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(AppSettings())
        val controller = TileController(window, store, onTap = {}, theme = ThemeMode.LIGHT, slopPx = TestData.SLOP_PX)

        fun shown(): Rig {
            assertEquals("overlay: show should report SHOWN", ShowResult.SHOWN, controller.show())
            return this
        }

        /** Forget the calls made so far, so a test reads only what its own step did. */
        fun forget() {
            window.calls.clear()
            window.frames.clear()
            window.appliedFaces.clear()
        }
    }

    private val sentence = "Turn dictation on first"
    private val wideFrame = FakeTileWindow.Frame(406, 1090, 300, 150)
    private val squareFrame = FakeTileWindow.Frame(506, 1140, 100, 100)

    /** If this fails, hide throws away the sentence the tile was showing, or the state with it, so the next show draws a plain tile. */
    @Test
    fun `a notice shown before hide is drawn again by the next show`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.ARMED)
        rig.controller.showNotice(sentence)
        rig.controller.hide()
        rig.forget()

        rig.controller.show()

        assertEquals("overlay: show should add, draw the kept face, then size the wide window", listOf("add", "applyFace", "setFrame"), rig.window.calls)
        val face = rig.window.appliedFaces.single()
        assertEquals("overlay: show should draw the sentence kept through hide", sentence, face.notice)
        assertEquals("overlay: show should draw the notice shape", TileShape.NOTICE, face.shape)
        assertEquals("overlay: show should draw the state kept through hide", TileState.ARMED, face.state)
        assertEquals("overlay: show should size the window for the notice", listOf(wideFrame), rig.window.frames)

        rig.forget()
        rig.controller.clearNotice()
        assertEquals("overlay: clearing the kept sentence should size the window then draw", listOf("setFrame", "applyFace"), rig.window.calls)
        assertEquals("overlay: clearing should bring the square window back", listOf(squareFrame), rig.window.frames)
    }

    /** If this fails, hide sets the meter back to empty, so a tile hidden in the middle of a recording comes back silent. */
    @Test
    fun `a level set before hide is drawn again by the next show`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.RECORDING)
        rig.controller.setLevel(0.5f)
        rig.controller.hide()
        rig.forget()

        rig.controller.show()

        val face = rig.window.appliedFaces.last()
        assertEquals("overlay: show should draw the recording state kept through hide", TileState.RECORDING, face.state)
        assertEquals("overlay: show should draw the level kept through hide, half of 12 segments", 6, face.litSegments)
    }

    /** If this fails, a sentence pushed while the tile is off screen is dropped instead of kept for the next show. */
    @Test
    fun `a notice pushed while hidden is drawn by the next show`() {
        val rig = Rig().shown()
        rig.controller.hide()
        rig.forget()

        rig.controller.showNotice(sentence)
        assertEquals("overlay: a push while hidden should call no window method", emptyList<String>(), rig.window.calls)

        rig.controller.show()
        assertEquals("overlay: show should add, draw the kept face, then size the wide window", listOf("add", "applyFace", "setFrame"), rig.window.calls)
        assertEquals("overlay: show should draw the sentence pushed while hidden", sentence, rig.window.appliedFaces.single().notice)
        assertEquals("overlay: show should size the window for the notice", listOf(wideFrame), rig.window.frames)
    }
}
