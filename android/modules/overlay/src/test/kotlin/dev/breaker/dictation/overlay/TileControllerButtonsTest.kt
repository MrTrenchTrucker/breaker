package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.TilePosition
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a touch means for each state and each part of the tile, and where the window is when it is read.
 *
 * The screen is the one in [TestData]: the saved centre puts the square tile at (506, 1140), 100 pixels
 * square. While recording (or showing a notice) the window is 300 by 150 pixels with its top-left corner
 * at (406, 1090): the cancel button is x 406..506 and the send button x 606..706, both y 1140..1240; the
 * microphone is x 506..606 (the square tile's own place); the strip is y 1090..1140.
 */
class TileControllerButtonsTest {

    private val mic = 556f to 1190f
    private val cancel = 456f to 1190f
    private val send = 656f to 1190f
    private val strip = 556f to 1110f
    private val outside = 900f to 1190f

    private class Rig(seed: AppSettings = AppSettings()) {
        val log = ArrayList<String>()
        var onBeginExtra: () -> Unit = {}
        val window = FakeTileWindow()
        val store = FakeSettingsStore(seed)
        val controller = TileController(
            window, store,
            onTap = { log.add("tap") },
            theme = ThemeMode.LIGHT,
            slopPx = TestData.SLOP_PX,
            onSaveFailed = { log.add("saveFailed") },
            onBegin = {
                log.add("begin")
                onBeginExtra()
            },
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

    // ---- which tap does what ----

    /** If this fails, an idle tile does not call onTap for the microphone, or calls another callback. */
    @Test
    fun `idle microphone calls onTap only`() {
        val rig = Rig().shown(TileState.IDLE)
        assertEquals("overlay: an idle tap on the microphone should call onTap once", listOf("tap"), rig.tapped(mic))
        assertEquals("overlay: a tap should not change the state", TileState.IDLE, rig.controller.state)
        assertTrue("overlay: a tap should move nothing", rig.window.moves.isEmpty())
        assertEquals("overlay: a tap should save nothing", 0, rig.store.saveCount)
    }

    /** If this fails, an armed tile does not begin recording on a tap, or the tap changes the state itself. */
    @Test
    fun `armed microphone calls onBegin only and the state stays armed`() {
        val rig = Rig().shown(TileState.ARMED)
        assertEquals("overlay: an armed tap on the microphone should call onBegin once", listOf("begin"), rig.tapped(mic))
        assertEquals("overlay: the app changes the state, not the tap", TileState.ARMED, rig.controller.state)
        assertEquals("overlay: the tap should not resize the window", 0, rig.window.frames.size)
    }

    /** If this fails, recording does not send on the microphone or the check, does not cancel on the X, or reacts in the strip or beside the window. */
    @Test
    fun `recording sends on the microphone and the check, cancels on the X and ignores the rest`() {
        val rig = Rig().shown(TileState.RECORDING)
        assertEquals("overlay: the microphone should send", listOf("send"), rig.tapped(mic))
        assertEquals("overlay: the check should send", listOf("send"), rig.tapped(send))
        assertEquals("overlay: the X should cancel", listOf("cancel"), rig.tapped(cancel))
        assertEquals("overlay: the strip should do nothing", emptyList<String>(), rig.tapped(strip))
        assertEquals("overlay: a point beside the window should do nothing", emptyList<String>(), rig.tapped(outside))
        assertEquals("overlay: the taps should not change the state", TileState.RECORDING, rig.controller.state)
        assertTrue("overlay: the taps should move nothing", rig.window.moves.isEmpty())
        assertEquals("overlay: the taps should save nothing", 0, rig.store.saveCount)
    }

    /** If this fails, a tile that is sending reacts to a tap, whatever it shows. */
    @Test
    fun `sending ignores every tap`() {
        val rig = Rig().shown(TileState.SENDING)
        assertEquals("overlay: the microphone should do nothing while sending", emptyList<String>(), rig.tapped(mic))
        rig.controller.showNotice("Please wait")
        assertEquals("overlay: the microphone should do nothing while sending with a notice", emptyList<String>(), rig.tapped(mic))
        assertEquals("overlay: the strip should do nothing while sending", emptyList<String>(), rig.tapped(strip))
    }

    /** If this fails, a failed tile does not pass the microphone tap to the app like an idle one. */
    @Test
    fun `failed microphone calls onTap like idle`() {
        val rig = Rig().shown(TileState.FAILED)
        assertEquals("overlay: a failed tap on the microphone should call onTap once", listOf("tap"), rig.tapped(mic))
    }

    /** If this fails, the notice window reacts to a part it does not draw, or the microphone stops answering under a notice. */
    @Test
    fun `under a notice only the microphone answers`() {
        val rig = Rig().shown(TileState.IDLE)
        rig.controller.showNotice("Turn dictation on first")
        assertEquals("overlay: the microphone under a notice should call onTap", listOf("tap"), rig.tapped(mic))
        assertEquals("overlay: the strip under a notice should do nothing", emptyList<String>(), rig.tapped(strip))
        assertEquals("overlay: the empty place of the cancel button should do nothing", emptyList<String>(), rig.tapped(cancel))
        assertEquals("overlay: the empty place of the check should do nothing", emptyList<String>(), rig.tapped(send))
    }

    /** If this fails, a missing callback crashes the tap instead of doing nothing. */
    @Test
    fun `a missing callback makes the tap do nothing`() {
        val window = FakeTileWindow()
        val store = FakeSettingsStore(AppSettings())
        val controller = TestData.controller(window, store)
        controller.show()
        controller.setState(TileState.ARMED)
        window.down(mic.first, mic.second)
        window.up(mic.first, mic.second)
        controller.setState(TileState.RECORDING)
        listOf(cancel, mic, send).forEach { window.down(it.first, it.second); window.up(it.first, it.second) }

        assertTrue("overlay: the tile should still be shown", controller.isShown)
        assertEquals("overlay: the taps should not change the state", TileState.RECORDING, controller.state)
        assertTrue("overlay: the taps should move nothing", window.moves.isEmpty())
        assertEquals("overlay: the taps should save nothing", 0, store.saveCount)
    }

    /** If this fails, a finger that wobbles inside the slop makes two calls or none. */
    @Test
    fun `one tap with a wobble makes one call`() {
        val rig = Rig().shown(TileState.RECORDING)
        rig.window.down(mic.first, mic.second)
        rig.window.move(mic.first + 3f, mic.second + 2f)
        rig.window.up(mic.first + 4f, mic.second + 2f)
        assertEquals("overlay: a wobbling tap should call once", listOf("send"), rig.log)
    }

    /** If this fails, a callback runs because the window was hidden, shown or told something, not because of a tap. */
    @Test
    fun `hide show and pushes call no callback`() {
        val rig = Rig().shown()
        TileState.values().forEach { rig.controller.setState(it) }
        rig.controller.setLevel(0.7f)
        rig.controller.showNotice("Turn dictation on first")
        rig.controller.clearNotice()
        rig.controller.setDescription("Dictation")
        rig.controller.setTheme(ThemeMode.DARK)
        rig.controller.onDisplayChanged()
        rig.window.down(cancel.first, cancel.second)
        rig.controller.hide()
        rig.controller.show()
        rig.controller.hide()
        assertEquals("overlay: no callback should run without a tap", emptyList<String>(), rig.log)
        assertEquals("overlay: nothing should be saved without a drag", 0, rig.store.saveCount)
    }

    /** If this fails, a callback that pushes the next state breaks the tap that called it. */
    @Test
    fun `a callback may push the next state`() {
        val rig = Rig().shown(TileState.ARMED)
        rig.onBeginExtra = { rig.controller.setState(TileState.RECORDING) }
        assertEquals("overlay: the armed tap should begin", listOf("begin"), rig.tapped(mic))
        assertEquals("overlay: the app's push inside the callback should take effect", TileState.RECORDING, rig.controller.state)
        assertEquals(
            "overlay: the window should widen once, for the recording shape",
            listOf(FakeTileWindow.Frame(406, 1090, 300, 150)),
            rig.window.frames,
        )
        assertEquals("overlay: the next tap should send", listOf("send"), rig.tapped(mic))
    }

    // ---- where the window is when the touch is read ----

    /** If this fails, a display change leaves the wide window where the old screen put it, or moves it as if it were the square tile. */
    @Test
    fun `a display change places the wide window in each shape`() {
        val seed = TestData.settings(TilePosition(0.25f, 0.75f))
        val wide = Rig(seed).shown(TileState.RECORDING)
        wide.window.bounds = PixelBounds(0, 0, 1200, 2200)
        wide.window.sizePx = 200
        wide.controller.onDisplayChanged()
        assertEquals("overlay: the wide window should be placed again once", listOf(FakeTileWindow.Frame(50, 1400, 600, 300)), wide.window.frames)
        assertTrue("overlay: the wide window should not be moved as a square tile", wide.window.moves.isEmpty())

        val square = Rig(seed).shown(TileState.ARMED)
        square.window.bounds = PixelBounds(0, 0, 1200, 2200)
        square.window.sizePx = 200
        square.controller.onDisplayChanged()
        assertEquals("overlay: the square tile should be moved once", listOf(PixelPoint(250, 1500)), square.window.moves)
        assertTrue("overlay: the square tile should not be resized", square.window.frames.isEmpty())
    }

    /** If this fails, a slide on a button blocks the display change that the wide window needs. */
    @Test
    fun `a display change in a slide on a button still places the window`() {
        val rig = Rig().shown(TileState.RECORDING)
        rig.window.down(cancel.first, cancel.second)
        rig.window.move(cancel.first, cancel.second + 100f)
        rig.window.bounds = PixelBounds(0, 0, 1200, 2200)
        rig.window.sizePx = 200
        rig.controller.onDisplayChanged()
        assertEquals("overlay: the wide window should be placed again", listOf(FakeTileWindow.Frame(300, 900, 600, 300)), rig.window.frames)
    }

    /** If this fails, a tap near the screen edge is read against the place the window would have had, not the place it has. */
    @Test
    fun `a tap is read against the window as it was kept on screen`() {
        val rig = Rig(TestData.settings(TilePosition(1.0f, 1.0f))).shown(TileState.IDLE)
        rig.controller.setState(TileState.RECORDING)
        assertEquals("overlay: the window should be pulled in from the right edge", listOf(FakeTileWindow.Frame(796, 2150, 300, 150)), rig.window.frames)
        // The square tile is at (996, 2200), so the microphone is x 896..996 and the cancel button x 796..896.
        assertEquals("overlay: x 946 is the microphone", listOf("send"), rig.tapped(946f to 2250f))
        assertEquals("overlay: x 846 is the cancel button", listOf("cancel"), rig.tapped(846f to 2250f))
        assertEquals("overlay: x 1046 is the check", listOf("send"), rig.tapped(1046f to 2250f))
    }

    /** If this fails, the cell edges are read wrong: the left edge is inside, the right edge is outside, and a point left of the window is outside. */
    @Test
    fun `cell edges are half open and a fraction left of the window is outside`() {
        val rig = Rig().shown(TileState.RECORDING)
        assertEquals("overlay: x 406 is the first pixel of the cancel button", listOf("cancel"), rig.tapped(406f to 1190f))
        assertEquals("overlay: half a pixel left of the window is outside it", emptyList<String>(), rig.tapped(405.5f to 1190f))
        assertEquals("overlay: x 505.9 is still the cancel button", listOf("cancel"), rig.tapped(505.9f to 1190f))
        assertEquals("overlay: x 506 is the first pixel of the microphone", listOf("send"), rig.tapped(506f to 1190f))
        assertEquals("overlay: y 1139.9 is still the strip", emptyList<String>(), rig.tapped(456f to 1139.9f))
        assertEquals("overlay: y 1140 is the first row of the cancel button", listOf("cancel"), rig.tapped(456f to 1140f))
        assertEquals("overlay: x 705.9 is still the check", listOf("send"), rig.tapped(705.9f to 1190f))
        assertEquals("overlay: x 706 is outside the window", emptyList<String>(), rig.tapped(706f to 1190f))
    }
}
