package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * A sentence on the tile is removed by the change of state that follows it, in particular by the change
 * into recording, which has no room for a sentence.
 *
 * The screen is the one in [TestData]: the saved centre puts the square tile at (506, 1140), 100 pixels
 * square, so the wide window of 300 by 150 pixels has its top-left corner at (406, 1090).
 */
class TileControllerNoticeClearTest {

    private class Rig {
        val window = FakeTileWindow()
        val controller = TileController(window, FakeSettingsStore(AppSettings()), onTap = {}, theme = ThemeMode.LIGHT, slopPx = TestData.SLOP_PX)

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

    private val wideFrame = FakeTileWindow.Frame(406, 1090, 300, 150)

    /** If this fails, a sentence shown before recording is carried into the recording face. */
    @Test
    fun `a sentence is gone from the face when recording starts`() {
        val rig = Rig().shown()
        rig.controller.showNotice("Turn dictation on first")
        rig.forget()

        rig.controller.setState(TileState.RECORDING)

        assertEquals("overlay: recording should size the window then draw", listOf("setFrame", "applyFace"), rig.window.calls)
        assertEquals("overlay: recording should have the wide frame", listOf(wideFrame), rig.window.frames)
        val face = rig.window.appliedFaces.single()
        assertEquals("overlay: the face should have the recording shape, not the notice shape", TileShape.RECORDING, face.shape)
        assertNull("overlay: the recording face should carry no sentence", face.notice)

        rig.controller.setLevel(0.5f)
        assertNull("overlay: a later recording face should carry no sentence either", rig.window.appliedFaces.last().notice)

        rig.controller.setState(TileState.IDLE)
        assertEquals("overlay: leaving recording should give the square tile", TileShape.COLLAPSED, rig.window.appliedFaces.last().shape)
        assertNull("overlay: leaving recording should show no sentence", rig.window.appliedFaces.last().notice)
    }

    /** If this fails, a sentence pushed while hidden survives a change into recording and is drawn by the next show. */
    @Test
    fun `a sentence pushed while hidden is gone when recording starts before the next show`() {
        val rig = Rig()
        rig.controller.showNotice("Turn dictation on first")
        rig.controller.setState(TileState.RECORDING)
        assertEquals("overlay: pushes while hidden should call no window method", emptyList<String>(), rig.window.calls)

        assertEquals("overlay: show should report SHOWN", ShowResult.SHOWN, rig.controller.show())

        val face = rig.window.appliedFaces.single()
        assertEquals("overlay: the first face should have the recording shape", TileShape.RECORDING, face.shape)
        assertNull("overlay: the first face should carry no sentence", face.notice)
    }
}
