package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which gestures may move the tile, and what a tap on the square tile means wherever it is read.
 *
 * The screen is the one in [TestData]: the saved centre puts the square tile at (506, 1140), 100 pixels
 * square. While recording (or showing a notice) the window is 300 by 150 pixels with its top-left corner
 * at (406, 1090): the cancel button is x 406..506 and the send button x 606..706, both y 1140..1240; the
 * microphone is x 506..606 (the square tile's own place); the strip is y 1090..1140.
 */
class TileControllerGestureRulesTest {

    private val mic = 556f to 1190f
    private val cancel = 456f to 1190f
    private val send = 656f to 1190f
    private val strip = 556f to 1110f
    private val far = 300f to 300f

    private class Rig(seed: AppSettings = AppSettings()) {
        val log = ArrayList<String>()
        val window = FakeTileWindow()
        val store = FakeSettingsStore(seed)
        val controller = TileController(
            window, store,
            onTap = { log.add("tap") },
            theme = ThemeMode.LIGHT,
            slopPx = TestData.SLOP_PX,
            onSaveFailed = { log.add("saveFailed") },
            onBegin = { log.add("begin") },
            onCancel = { log.add("cancel") },
            onSend = { log.add("send") },
        )

        /** Show the tile, push [state] and forget the calls made so far. */
        fun shown(state: TileState = TileState.IDLE): Rig {
            assertEquals("overlay: show should report SHOWN", ShowResult.SHOWN, controller.show())
            controller.setState(state)
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

        /** Tap [p] and return what the callbacks logged, clearing the log. */
        fun tapped(p: Pair<Float, Float>): List<String> {
            window.down(p.first, p.second)
            window.up(p.first, p.second)
            return log.toList().also { log.clear() }
        }
    }

    // ---- which gestures may move the tile ----

    /** If this fails, a slide that starts on a button or the microphone of the wide tile moves it or saves a position. */
    @Test
    fun `a slide on the wide tile never moves it or saves`() {
        val starts = listOf(cancel, mic, send, strip)
        starts.forEach { start ->
            val rig = Rig().shown(TileState.RECORDING)
            rig.window.down(start.first, start.second)
            rig.window.move(start.first + 20f, start.second + 100f)
            rig.window.move(start.first + 40f, start.second + 200f)
            rig.window.up(start.first + 40f, start.second + 200f)
            assertEquals("overlay: a slide from $start should call no window method", emptyList<String>(), rig.window.calls)
            assertEquals("overlay: a slide from $start should save nothing", 0, rig.store.saveCount)
            assertEquals("overlay: a slide from $start should call no callback", emptyList<String>(), rig.log)
        }
        val notice = Rig().shown(TileState.ARMED)
        notice.controller.showNotice("Turn dictation on first")
        notice.forget()
        notice.window.down(mic.first, mic.second)
        notice.window.move(mic.first + 20f, mic.second + 100f)
        notice.window.up(mic.first + 20f, mic.second + 100f)
        assertEquals("overlay: a slide on the notice window should call no window method", emptyList<String>(), notice.window.calls)
        assertEquals("overlay: a slide on the notice window should save nothing", 0, notice.store.saveCount)
        assertEquals("overlay: a slide on the notice window should call no callback", emptyList<String>(), notice.log)
    }

    /** If this fails, the small tile no longer drags and saves as it did before the wide shapes. */
    @Test
    fun `the square tile still drags and saves once`() {
        val rig = Rig().shown(TileState.ARMED)
        rig.window.down(mic.first, mic.second)
        rig.window.move(mic.first + 20f, mic.second + 20f)
        rig.window.up(mic.first + 20f, mic.second + 20f)
        assertEquals("overlay: the drag should move the square tile by the finger", listOf(PixelPoint(526, 1160)), rig.window.moves)
        assertEquals("overlay: the drag should save once", 1, rig.store.saveCount)
        assertEquals("overlay: a drag should call no callback", emptyList<String>(), rig.log)
    }

    /** If this fails, a press that began on the square tile is routed as a tap in the wide shape that arrived under the finger. */
    @Test
    fun `a shape change under a pressed finger cancels the gesture`() {
        val rig = Rig().shown(TileState.ARMED)
        rig.window.down(mic.first, mic.second)
        rig.controller.setState(TileState.RECORDING)
        rig.window.up(mic.first, mic.second)
        assertEquals("overlay: the release after a shape change should call no callback", emptyList<String>(), rig.log)
    }

    /** If this fails, a drag cut short by a new shape loses its position, or a slide on a button saves one. */
    @Test
    fun `a shape change in a drag saves only the square tile's drag`() {
        val square = Rig().shown(TileState.ARMED)
        square.window.down(300f, 300f)
        square.window.move(380f, 360f)
        assertEquals("overlay: the drag should have moved the tile", listOf(PixelPoint(586, 1200)), square.window.moves)
        square.controller.showNotice("Turn dictation on first")
        assertEquals("overlay: the new shape should save the dragged position at once", 1, square.store.saveCount)
        assertEquals(
            "overlay: the wide window should surround the dragged tile",
            listOf(FakeTileWindow.Frame(486, 1150, 300, 150)),
            square.window.frames,
        )
        square.window.up(380f, 360f)
        assertEquals("overlay: the release after the cut should save nothing more", 1, square.store.saveCount)
        assertEquals("overlay: the cut drag should call no callback", emptyList<String>(), square.log)

        val wide = Rig().shown(TileState.RECORDING)
        wide.window.down(cancel.first, cancel.second)
        wide.window.move(cancel.first, cancel.second + 100f)
        wide.controller.setState(TileState.SENDING)
        assertEquals("overlay: a slide on a button should not be saved when the shape changes", 0, wide.store.saveCount)
        wide.window.up(cancel.first, cancel.second + 100f)
        assertTrue("overlay: the release after the cut should move nothing", wide.window.moves.isEmpty())
        assertEquals("overlay: the release after the cut should save nothing", 0, wide.store.saveCount)
        assertEquals("overlay: the cut slide should call no callback", emptyList<String>(), wide.log)
    }

    /** If this fails, hiding the wide tile fires a callback, saves a slide, or leaves the pressed finger alive for the next show. */
    @Test
    fun `hide while recording removes the window once and clears the gesture`() {
        val rig = Rig().shown(TileState.RECORDING)
        rig.window.down(cancel.first, cancel.second)
        rig.controller.hide()
        assertEquals("overlay: hide should only remove the window", listOf("remove"), rig.window.calls)
        assertEquals("overlay: hide should call no callback", emptyList<String>(), rig.log)
        rig.controller.show()
        assertEquals("overlay: show should draw the recording tile again", TileState.RECORDING, rig.window.appliedFaces.last().state)
        rig.window.up(cancel.first, cancel.second)
        assertEquals("overlay: the release after hide and show should call no callback", emptyList<String>(), rig.log)

        val slide = Rig().shown(TileState.RECORDING)
        slide.window.down(mic.first, mic.second)
        slide.window.move(mic.first, mic.second + 100f)
        slide.controller.hide()
        assertEquals("overlay: hiding in a slide on the wide tile should save nothing", 0, slide.store.saveCount)
    }

    // ---- a tap on the square tile is a tap on the microphone ----

    /** If this fails, a tap on the square tile is read against the cells of a wide window, so a tap away from the microphone's place does nothing. */
    @Test
    fun `a tap anywhere on the square tile is a tap on the microphone`() {
        val expected = linkedMapOf(
            TileState.IDLE to listOf("tap"),
            TileState.ARMED to listOf("begin"),
            TileState.FAILED to listOf("tap"),
            TileState.SENDING to emptyList<String>(),
        )
        expected.forEach { (state, calls) ->
            val rig = Rig().shown(state)
            assertEquals("overlay: a tap far from the square tile while $state should call $calls", calls, rig.tapped(far))
            assertEquals("overlay: the tap while $state should not change the state", state, rig.controller.state)
            assertTrue("overlay: the tap while $state should move nothing", rig.window.moves.isEmpty())
            assertTrue("overlay: the tap while $state should not resize the window", rig.window.frames.isEmpty())
            assertEquals("overlay: the tap while $state should save nothing", 0, rig.store.saveCount)
        }
    }

    /** If this fails, only some points of the square tile's window count as the microphone, so a tap near a corner of the screen is lost. */
    @Test
    fun `an idle tap at any point calls onTap once each time`() {
        val rig = Rig().shown(TileState.IDLE)
        val points = listOf(0f to 0f, far, 1000f to 2000f, 505.9f to 1139.9f, 606f to 1240f)
        points.forEach { point ->
            assertEquals("overlay: an idle tap at $point should call onTap once", listOf("tap"), rig.tapped(point))
        }
    }

    /** If this fails, the wide window under a notice answers for points it does not cover, as the square tile does. */
    @Test
    fun `a tap away from the notice window does nothing`() {
        val rig = Rig().shown(TileState.IDLE)
        rig.controller.showNotice("Turn dictation on first")
        assertEquals("overlay: a tap far from the notice window should do nothing", emptyList<String>(), rig.tapped(far))
        assertEquals("overlay: a tap on the microphone under the notice should still call onTap", listOf("tap"), rig.tapped(mic))
    }
}
