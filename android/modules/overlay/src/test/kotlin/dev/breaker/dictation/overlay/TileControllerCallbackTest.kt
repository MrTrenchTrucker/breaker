package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A callback of the app that throws is not caught by the tile: the exception goes to whoever delivered the touch,
 * and the tile keeps working for the next one.
 *
 * The screen is the one in [TestData]: the saved centre puts the square tile at (506, 1140), 100 pixels square.
 * While recording the window is 300 by 150 pixels with its top-left corner at (406, 1090), so the cancel button
 * is x 406..506, the microphone x 506..606 and the send button x 606..706, all at y 1140..1240.
 */
class TileControllerCallbackTest {

    private val mic = 556f to 1190f
    private val cancel = 456f to 1190f
    private val send = 656f to 1190f

    private class Rig(state: TileState) {
        val log = ArrayList<String>()
        var failing = true
        val failure = IllegalStateException("overlay: the callback of the app failed")
        val window = FakeTileWindow()
        val store = FakeSettingsStore(AppSettings())
        val controller = TileController(
            window, store,
            onTap = { fire("tap") },
            theme = ThemeMode.LIGHT,
            slopPx = TestData.SLOP_PX,
            onBegin = { fire("begin") },
            onCancel = { fire("cancel") },
            onSend = { fire("send") },
        )

        init {
            controller.show()
            controller.setState(state)
        }

        private fun fire(name: String) {
            log.add(name)
            if (failing) throw failure
        }

        /** Press and lift at [p]; the exception of a failing callback, or null. */
        fun tapCaught(p: Pair<Float, Float>): Throwable? {
            window.down(p.first, p.second)
            return try {
                window.up(p.first, p.second)
                null
            } catch (e: Throwable) {
                e
            }
        }
    }

    private class Case(val label: String, val state: TileState, val at: Pair<Float, Float>, val callback: String)

    private val cases = listOf(
        Case("onTap, idle tile, microphone", TileState.IDLE, mic, "tap"),
        Case("onTap, failed tile, microphone", TileState.FAILED, mic, "tap"),
        Case("onBegin, armed tile, microphone", TileState.ARMED, mic, "begin"),
        Case("onSend, recording tile, microphone", TileState.RECORDING, mic, "send"),
        Case("onSend, recording tile, send button", TileState.RECORDING, send, "send"),
        Case("onCancel, recording tile, cancel button", TileState.RECORDING, cancel, "cancel"),
    )

    /** If this fails, the tile catches the exception of a callback, so the app never learns that its own code failed. */
    @Test
    fun `an exception thrown by a callback reaches the caller of the touch`() {
        for (case in cases) {
            val rig = Rig(case.state)
            val thrown = rig.tapCaught(case.at)
            assertSame("overlay: ${case.label}: the exception of the callback should reach the caller", rig.failure, thrown)
            assertEquals("overlay: ${case.label}: the callback should have run once", listOf(case.callback), rig.log)
        }
    }

    /** If this fails, a callback that threw leaves the tile hidden, changed or unable to take the next tap. */
    @Test
    fun `the tile keeps working after a callback threw`() {
        val rig = Rig(TileState.RECORDING)
        assertSame("overlay: the first send should throw to the caller", rig.failure, rig.tapCaught(send))

        rig.failing = false
        assertNull("overlay: the next tap should run without an exception", rig.tapCaught(cancel))
        assertEquals("overlay: the callbacks should have run once each, in order", listOf("send", "cancel"), rig.log)
        assertEquals("overlay: the failing callback should not change the state", TileState.RECORDING, rig.controller.state)
        assertTrue("overlay: the tile should still be shown", rig.controller.isShown)
        assertEquals("overlay: the window should not be removed", 0, rig.window.removeCount)
        assertEquals("overlay: nothing should be saved", 0, rig.store.saveCount)
        assertTrue("overlay: the tile should not have moved", rig.window.moves.isEmpty())
    }
}
