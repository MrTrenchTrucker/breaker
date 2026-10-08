package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Two things the controller must not do when nothing has changed for the window: draw the same face
 * again after a theme change, and act on a touch that came while the tile was hidden.
 *
 * The screen is the one in [TestData]: the saved centre puts the square tile at (506, 1140), 100 pixels
 * square. While recording the window is 300 by 150 pixels with its top-left corner at (406, 1090): the
 * cancel button is x 406..506, the microphone x 506..606 and the send button x 606..706, all in
 * y 1140..1240.
 */
class TileControllerHiddenTouchTest {

    private class Rig {
        val log = ArrayList<String>()
        val window = FakeTileWindow()
        val store = FakeSettingsStore(AppSettings())
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

        /** Show the tile and forget the calls made so far. */
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
            log.clear()
        }

        fun tap(x: Float, y: Float) {
            window.down(x, y)
            window.up(x, y)
        }
    }

    private val mic = 556f to 1190f
    private val cancel = 456f to 1190f
    private val send = 656f to 1190f

    // ---- a theme change remembers the face it drew

    /** The window calls made by [push] on a shown tile right after its theme changed. */
    private fun callsAfterThemeChange(push: (TileController) -> Unit): List<String> {
        val rig = Rig().shown()
        rig.controller.setTheme(ThemeMode.DARK)
        assertEquals("overlay: a theme change should repaint then draw the face", listOf("applyPalette", "applyFace"), rig.window.calls)
        rig.forget()
        push(rig.controller)
        return rig.window.calls.toList()
    }

    /** If this fails, a theme change redraws the tile but forgets that it did, so the next push that changes nothing redraws it again. */
    @Test
    fun `a push that changes nothing after a theme change draws nothing`() {
        assertEquals(
            "overlay: a level on an idle tile after a theme change should call nothing",
            emptyList<String>(),
            callsAfterThemeChange { it.setLevel(0.4f) },
        )
        assertEquals(
            "overlay: the same state after a theme change should call nothing",
            emptyList<String>(),
            callsAfterThemeChange { it.setState(TileState.IDLE) },
        )
        assertEquals(
            "overlay: clearing a notice that is not there after a theme change should call nothing",
            emptyList<String>(),
            callsAfterThemeChange { it.clearNotice() },
        )
        assertEquals(
            "overlay: the same description after a theme change should call nothing",
            emptyList<String>(),
            callsAfterThemeChange { it.setDescription(null) },
        )
    }

    // ---- a touch while hidden is dropped

    /** If this fails, a finger that went down while the tile was hidden is remembered and becomes a tap when the tile is shown and the finger comes up. */
    @Test
    fun `a finger pressed while hidden is not a tap after the tile is shown again`() {
        val rig = Rig().shown()
        rig.controller.hide()

        rig.window.down(mic.first, mic.second)
        assertEquals("overlay: show should report SHOWN", ShowResult.SHOWN, rig.controller.show())
        rig.window.up(mic.first, mic.second)

        assertEquals("overlay: a release with no press on the shown tile should call nothing", emptyList<String>(), rig.log)
        assertEquals("overlay: a release with no press should save nothing", 0, rig.store.saveCount)

        rig.tap(mic.first, mic.second)
        assertEquals("overlay: control: a full tap on the shown tile should call onTap once", listOf("tap"), rig.log)
    }

    /** If this fails, a press on a button of the recording window while hidden becomes a tap on that button once the tile is shown again. */
    @Test
    fun `a button pressed while hidden is not a tap after the tile is shown again`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.RECORDING)
        rig.controller.hide()

        rig.window.down(cancel.first, cancel.second)
        assertEquals("overlay: show should report SHOWN", ShowResult.SHOWN, rig.controller.show())
        rig.window.up(cancel.first, cancel.second)

        assertEquals("overlay: a release with no press on the recording tile should call nothing", emptyList<String>(), rig.log)

        rig.tap(cancel.first, cancel.second)
        assertEquals("overlay: control: a full tap on the cancel button should call onCancel once", listOf("cancel"), rig.log)
    }

    /** If this fails, a touch on the old place of the tile reaches a callback or the window while the tile is hidden. */
    @Test
    fun `touches on the old place of a hidden tile call nothing`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.RECORDING)
        rig.controller.hide()
        rig.forget()

        listOf(cancel, mic, send).forEach { point ->
            rig.window.down(point.first, point.second)
            rig.window.move(point.first + 30f, point.second)
            rig.window.up(point.first, point.second)
        }
        rig.window.down(mic.first, mic.second)
        rig.window.cancel()

        assertEquals("overlay: a hidden tile should call no callback", emptyList<String>(), rig.log)
        assertEquals("overlay: a hidden tile should make no window call", emptyList<String>(), rig.window.calls)
        assertEquals("overlay: a hidden tile should save nothing", 0, rig.store.saveCount)
    }
}
