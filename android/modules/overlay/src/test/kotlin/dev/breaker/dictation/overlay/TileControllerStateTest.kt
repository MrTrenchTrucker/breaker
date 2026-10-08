package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app pushes to the controller (state, level, description, theme) and what the window is told
 * in return: which calls, in which order, and when none at all.
 *
 * The screen is the one in [TestData]: the saved centre puts the square tile at (506, 1140), 100 pixels
 * square, so the wide window of 300 by 150 pixels has its top-left corner at (406, 1090).
 */
class TileControllerStateTest {

    private class Rig(seed: AppSettings = AppSettings()) {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(seed)
        val controller = TileController(window, store, onTap = {}, theme = ThemeMode.LIGHT, slopPx = TestData.SLOP_PX)

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

        /** The number of lit segments of the face drawn last. */
        fun lit(): Int = window.appliedFaces.last().litSegments
    }

    private val collapsedFrame = FakeTileWindow.Frame(506, 1140, 100, 100)
    private val wideFrame = FakeTileWindow.Frame(406, 1090, 300, 150)

    /** If this fails, a tile that was never told anything starts in a state other than idle. */
    @Test
    fun `the state starts idle and the first face shows it`() {
        val rig = Rig()
        assertEquals("overlay: a new controller should be idle", TileState.IDLE, rig.controller.state)
        rig.controller.show()
        assertEquals("overlay: show should add then draw, and nothing more for a square tile", listOf("add", "applyFace"), rig.window.calls)
        val face = rig.window.appliedFaces.single()
        assertEquals("overlay: the first face should be idle", TileState.IDLE, face.state)
        assertEquals("overlay: the first face should be the square tile", TileShape.COLLAPSED, face.shape)
        assertEquals("overlay: nothing is lit while idle", 0, face.litSegments)
        assertEquals("overlay: the meter has 12 segments", 12, face.segments)
        assertNull("overlay: the first face has no notice", face.notice)
        assertNull("overlay: the first face has no description", face.description)
        assertEquals("overlay: the first face uses the light theme", TileStyle.look(TileState.IDLE, TruckingTokens.palette(ThemeMode.LIGHT)), face.look)
    }

    /** If this fails, a push while hidden reaches a window that is not there, or is lost before the next show. */
    @Test
    fun `pushes while hidden touch no window and the next show draws them`() {
        val rig = Rig()
        rig.controller.setState(TileState.ARMED)
        rig.controller.setLevel(0.9f)
        rig.controller.showNotice("Turn dictation on first")
        rig.controller.clearNotice()
        rig.controller.setDescription("Dictation is on")
        assertEquals("overlay: a push while hidden should call no window method", emptyList<String>(), rig.window.calls)
        assertEquals("overlay: the state should be kept while hidden", TileState.ARMED, rig.controller.state)
        assertEquals("overlay: a push while hidden should not read the settings", 0, rig.store.loadCount)

        rig.controller.show()
        assertEquals("overlay: show should add then draw", listOf("add", "applyFace"), rig.window.calls)
        val face = rig.window.appliedFaces.single()
        assertEquals("overlay: the pushed state should be drawn", TileState.ARMED, face.state)
        assertEquals("overlay: the pushed description should be drawn", "Dictation is on", face.description)
    }

    /** If this fails, show sizes the window before it draws, or sizes a square tile that needs no resize. */
    @Test
    fun `show adds then draws then resizes only when the shape is wide`() {
        val notice = Rig()
        notice.controller.showNotice("Turn dictation on first")
        notice.controller.show()
        assertEquals("overlay: a wide shape should be sized after the draw", listOf("add", "applyFace", "setFrame"), notice.window.calls)
        assertEquals("overlay: the window should still be added at the square tile", 506, notice.window.adds.single().x)
        assertEquals("overlay: the window should still be added at the square tile", 1140, notice.window.adds.single().y)
        assertEquals("overlay: the notice window should surround the square tile", listOf(wideFrame), notice.window.frames)

        val recording = Rig()
        recording.controller.setState(TileState.RECORDING)
        recording.controller.show()
        assertEquals("overlay: recording should be sized after the draw", listOf("add", "applyFace", "setFrame"), recording.window.calls)
        assertEquals("overlay: the recording window should surround the square tile", listOf(wideFrame), recording.window.frames)
        assertEquals("overlay: the recording face should be drawn", TileShape.RECORDING, recording.window.appliedFaces.single().shape)

        val square = Rig()
        square.controller.setState(TileState.FAILED)
        square.controller.show()
        assertTrue("overlay: a square tile should not be resized", square.window.frames.isEmpty())
    }

    /** If this fails, the sentence for a tap that did nothing outlives the state change that answered it. */
    @Test
    fun `a different state removes the notice and a same state keeps it`() {
        val rig = Rig().shown()
        rig.controller.showNotice("Turn dictation on first")
        assertEquals("overlay: a notice should widen the window", listOf("setFrame", "applyFace"), rig.window.calls)
        rig.forget()

        rig.controller.setState(TileState.IDLE)
        assertEquals("overlay: pushing the state the tile already has should call nothing", emptyList<String>(), rig.window.calls)

        rig.controller.setState(TileState.ARMED)
        assertEquals("overlay: a new state should shrink the window and then draw", listOf("setFrame", "applyFace"), rig.window.calls)
        assertEquals("overlay: the window should be the square tile again", listOf(collapsedFrame), rig.window.frames)
        assertNull("overlay: a new state should remove the notice", rig.window.appliedFaces.last().notice)
        assertEquals("overlay: the square tile should be drawn armed", TileState.ARMED, rig.window.appliedFaces.last().state)
    }

    /** If this fails, the meter keeps the old level when a new recording starts. */
    @Test
    fun `the level goes back to zero when the state leaves recording`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.RECORDING)
        rig.controller.setLevel(1.0f)
        assertEquals("overlay: a full level should light every segment", 12, rig.lit())

        rig.controller.setState(TileState.ARMED)
        assertEquals("overlay: nothing is lit outside recording", 0, rig.lit())
        rig.controller.setState(TileState.RECORDING)
        assertEquals("overlay: a new recording should start with an empty meter", 0, rig.lit())
    }

    /** If this fails, a level pushed before recording starts shows up on the first recording face. */
    @Test
    fun `a level pushed before recording is dropped when recording starts`() {
        val rig = Rig().shown()
        rig.controller.setLevel(0.9f)
        rig.controller.setState(TileState.RECORDING)
        assertEquals("overlay: a level pushed while idle should not light the first recording face", 0, rig.lit())

        rig.controller.setLevel(0.5f)
        assertEquals("overlay: a level pushed while recording should light the meter", 6, rig.lit())
    }

    /** If this fails, a level pushed outside recording lights the meter or redraws the tile. */
    @Test
    fun `a level outside recording draws nothing`() {
        listOf(TileState.IDLE, TileState.ARMED, TileState.SENDING, TileState.FAILED).forEach { state ->
            val rig = Rig().shown()
            rig.controller.setState(state)
            rig.forget()
            rig.controller.setLevel(0.8f)
            rig.controller.setLevel(0.2f)
            assertEquals("overlay: a level in $state should call no window method", emptyList<String>(), rig.window.calls)
        }
    }

    /** If this fails, the tile redraws on every level, or misses a level that changes how many segments are lit. */
    @Test
    fun `the tile redraws only when the lit count changes`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.RECORDING)
        rig.forget()

        rig.controller.setLevel(0.5f)
        assertEquals("overlay: half a level should draw once", listOf("applyFace"), rig.window.calls)
        assertEquals("overlay: half a level should light 6 segments", 6, rig.lit())

        rig.controller.setLevel(0.45f)
        rig.controller.setLevel(0.46f)
        assertEquals("overlay: a level in the same segment should draw nothing more", listOf("applyFace"), rig.window.calls)

        rig.controller.setLevel(0.51f)
        assertEquals("overlay: a level in the next segment should draw once more", listOf("applyFace", "applyFace"), rig.window.calls)
        assertEquals("overlay: 0.51 should light 7 segments", 7, rig.lit())
        assertTrue("overlay: a level should never resize the window", rig.window.frames.isEmpty())
    }

    /** If this fails, a level that is not a number, below 0 or above 1 breaks the meter instead of being held to 0..1. */
    @Test
    fun `levels outside zero to one are held and not a number is zero`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.RECORDING)

        val steps = listOf(
            0.5f to 6, Float.NaN to 0, 0.5f to 6, -0.5f to 0, 2.0f to 12,
            Float.NEGATIVE_INFINITY to 0, Float.POSITIVE_INFINITY to 12, 0.0f to 0, 1.0f to 12, 0.01f to 1,
        )
        steps.forEach { (level, expected) ->
            rig.controller.setLevel(level)
            assertEquals("overlay: level $level should light $expected segments", expected, rig.lit())
        }
    }

    /** If this fails, the description is lost, or the tile redraws for a description it already shows. */
    @Test
    fun `the description reaches the face and draws only when it changes`() {
        val rig = Rig().shown()
        rig.controller.setDescription("Dictation is on")
        assertEquals("overlay: a new description should draw once", listOf("applyFace"), rig.window.calls)
        assertEquals("overlay: the description should be in the face", "Dictation is on", rig.window.appliedFaces.last().description)

        rig.controller.setDescription("Dictation is on")
        assertEquals("overlay: the same description should draw nothing more", listOf("applyFace"), rig.window.calls)

        rig.controller.setDescription(null)
        assertEquals("overlay: removing the description should draw once", listOf("applyFace", "applyFace"), rig.window.calls)
        assertNull("overlay: a null description should leave none in the face", rig.window.appliedFaces.last().description)
    }

    /** If this fails, a theme change repaints the colours but leaves the face in the old ones. */
    @Test
    fun `a theme change while shown re-applies the face in the new colours`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.FAILED)
        rig.forget()

        rig.controller.setTheme(ThemeMode.DARK)
        assertEquals("overlay: a theme change should repaint then draw the face", listOf("applyPalette", "applyFace"), rig.window.calls)
        assertEquals(
            "overlay: the face should use the dark colours of the current state",
            TileStyle.look(TileState.FAILED, TruckingTokens.palette(ThemeMode.DARK)),
            rig.window.appliedFaces.last().look,
        )
        assertEquals("overlay: the face should keep the state", TileState.FAILED, rig.window.appliedFaces.last().state)
    }

    /** If this fails, a theme set while hidden reaches the window, or the next show ignores it. */
    @Test
    fun `a theme set while hidden is used by the next face`() {
        val rig = Rig()
        rig.controller.setTheme(ThemeMode.DARK)
        assertEquals("overlay: a theme set while hidden should call no window method", emptyList<String>(), rig.window.calls)
        rig.controller.show()
        assertEquals(
            "overlay: the first face should use the theme set while hidden",
            TileStyle.look(TileState.IDLE, TruckingTokens.palette(ThemeMode.DARK)),
            rig.window.appliedFaces.single().look,
        )
    }

    /** If this fails, hide forgets what the app pushed, or show does not draw it again. */
    @Test
    fun `hide keeps what was pushed and the next show draws it again`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.RECORDING)
        rig.controller.setLevel(0.5f)
        rig.controller.setDescription("Recording")
        rig.forget()

        rig.controller.hide()
        assertEquals("overlay: hide should only remove the window", listOf("remove"), rig.window.calls)
        assertEquals("overlay: hide should keep the state", TileState.RECORDING, rig.controller.state)
        rig.controller.setLevel(0.9f)
        assertEquals("overlay: a push while hidden should call nothing", listOf("remove"), rig.window.calls)

        rig.controller.show()
        assertEquals(
            "overlay: show should add, draw the kept face, then size the wide window",
            listOf("remove", "add", "applyFace", "setFrame"),
            rig.window.calls,
        )
        val face = rig.window.appliedFaces.last()
        assertEquals("overlay: show should draw the kept state", TileState.RECORDING, face.state)
        assertEquals("overlay: show should draw the level pushed while hidden", 11, face.litSegments)
        assertEquals("overlay: show should draw the kept description", "Recording", face.description)
        assertEquals("overlay: show should size the wide window around the square tile", listOf(wideFrame), rig.window.frames)
    }

    /** If this fails, show draws only a face that differs from the one drawn before hide, so a window added again stays blank. */
    @Test
    fun `show draws again even when the face is the one drawn before hide`() {
        val rig = Rig().shown()
        rig.controller.hide()
        rig.forget()
        rig.controller.show()
        assertEquals("overlay: show after hide should draw the unchanged face again", listOf("add", "applyFace"), rig.window.calls)
    }
}
