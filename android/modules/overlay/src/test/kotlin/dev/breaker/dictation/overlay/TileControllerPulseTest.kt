package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When the controller asks the window to pulse the armed ring. A pulse is asked for only while an armed
 * tile is shown, the request comes before the face is drawn, hiding the tile asks for a stop, and a tap
 * means the same thing whatever the pulse request is.
 */
class TileControllerPulseTest {

    private val mic = 556f to 1190f

    private class Rig {
        val log = ArrayList<String>()
        val window = FakeTileWindow()
        val store = FakeSettingsStore(AppSettings())
        val controller = TileController(
            window,
            store,
            onTap = { log.add("tap") },
            theme = ThemeMode.LIGHT,
            slopPx = TestData.SLOP_PX,
            onBegin = { log.add("begin") },
            onCancel = { log.add("cancel") },
            onSend = { log.add("send") },
        )

        /** Show the tile and forget what the window was told so far. */
        fun shown(): Rig {
            assertEquals("overlay: show should report SHOWN", ShowResult.SHOWN, controller.show())
            forget()
            return this
        }

        fun forget() {
            window.pulses.clear()
            window.order.clear()
            window.calls.clear()
            log.clear()
        }

        /** Tap [p] and return what the callbacks logged, clearing the log. */
        fun tapped(p: Pair<Float, Float>): List<String> {
            window.down(p.first, p.second)
            window.up(p.first, p.second)
            return log.toList().also { log.clear() }
        }
    }

    /** A failure means a shown armed tile does not ask for the pulse, or asks after its face is drawn. */
    @Test
    fun `an armed tile that is shown asks for the pulse just before its face`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.ARMED)
        assertEquals("overlay: pushing armed on a shown tile should ask for the pulse once", listOf(true), rig.window.pulses)
        assertEquals("overlay: the pulse request should come before the face", listOf("pulse:true", "face"), rig.window.order)
    }

    /** A failure means a state other than armed asks for a pulse while the tile is shown. */
    @Test
    fun `no state other than armed asks for the pulse while the tile is shown`() {
        for (state in TileState.values()) {
            if (state == TileState.ARMED) continue
            val rig = Rig().shown()
            rig.controller.setState(state)
            assertTrue("overlay: a shown $state tile must not ask for a pulse, got ${rig.window.pulses}", rig.window.pulses.none { it })
        }
    }

    /** A failure means the pulse keeps running after the tile leaves the armed state, or the stop comes after the face. */
    @Test
    fun `leaving armed asks the window to stop the pulse before the next face`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.ARMED)
        rig.controller.setState(TileState.RECORDING)
        assertEquals("overlay: the pulse should be asked on and then off", listOf(true, false), rig.window.pulses)
        assertEquals(
            "overlay: each pulse request should come before its face",
            listOf("pulse:true", "face", "pulse:false", "face"),
            rig.window.order,
        )
    }

    /** A failure means an armed tile pushed while hidden does not pulse once it is shown, or the request comes after the face. */
    @Test
    fun `an armed tile pushed while hidden asks for the pulse on show before its face`() {
        val rig = Rig()
        rig.controller.setState(TileState.ARMED)
        assertTrue("overlay: a push while hidden should ask the window for nothing", rig.window.order.isEmpty())
        assertEquals("overlay: show should report SHOWN", ShowResult.SHOWN, rig.controller.show())
        assertEquals("overlay: show should ask for the pulse once", listOf(true), rig.window.pulses)
        assertEquals("overlay: show should ask for the pulse before the face", listOf("pulse:true", "face"), rig.window.order)
    }

    /** A failure means a theme change on an armed tile draws the face without first asking for the pulse. */
    @Test
    fun `a theme change while armed asks for the pulse before the face is drawn again`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.ARMED)
        rig.forget()
        rig.controller.setTheme(ThemeMode.DARK)
        assertEquals("overlay: a theme change should ask for the pulse before the face", listOf("pulse:true", "face"), rig.window.order)
    }

    /** A failure means hiding the tile leaves the pulse asked on, or asks for a stop more than once. */
    @Test
    fun `hide asks the window to stop the pulse once`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.ARMED)
        rig.forget()
        rig.controller.hide()
        assertEquals("overlay: hide should ask for the pulse to stop once", listOf(false), rig.window.pulses)
        assertEquals("overlay: hide should draw nothing and only stop the pulse", listOf("pulse:false"), rig.window.order)
        assertEquals("overlay: hide should remove the window once", listOf("remove"), rig.window.calls)
    }

    /** A failure means a repeated armed push that changes nothing the tile shows asks the window again. */
    @Test
    fun `a repeated armed push changes nothing and the pulse stays on`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.ARMED)
        rig.forget()
        rig.controller.setState(TileState.ARMED)
        rig.controller.setLevel(0.7f)
        assertTrue("overlay: a repeated armed push and a level outside recording should call no window method", rig.window.order.isEmpty())
        assertTrue("overlay: a repeated armed push should not stop the pulse", rig.window.pulses.isEmpty())
    }

    /** A failure means a tap changes the pulse request, or an armed tap answers something other than the begin of recording. */
    @Test
    fun `tap answers do not change with the pulse asked on or off`() {
        val armed = Rig().shown()
        armed.controller.setState(TileState.ARMED)
        armed.forget()
        assertEquals("overlay: an armed tap on the microphone should begin", listOf("begin"), armed.tapped(mic))
        assertEquals("overlay: a second armed tap should begin again", listOf("begin"), armed.tapped(mic))
        assertTrue("overlay: taps should ask the window for nothing", armed.window.order.isEmpty())
        assertEquals("overlay: a tap should not change the state", TileState.ARMED, armed.controller.state)

        val idle = Rig().shown()
        assertEquals("overlay: an idle tap on the microphone should call onTap", listOf("tap"), idle.tapped(mic))
        assertTrue("overlay: an idle tap should ask the window for nothing", idle.window.order.isEmpty())
    }
}
