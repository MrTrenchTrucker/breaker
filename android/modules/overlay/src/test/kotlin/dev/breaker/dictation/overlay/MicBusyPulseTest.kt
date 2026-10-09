package dev.breaker.dictation.overlay

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.shared.tokens.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The busy pulse lifecycle on TileController: when the state enters or leaves MIC_BUSY, the
 * controller asks the window to start or stop the busy pulse. The busy pulse is a slower,
 * shallower pulse than the armed pulse, drawn while the microphone is busy.
 *
 * These tests use the same Rig pattern as [TileControllerPulseTest]: a FakeTileWindow records
 * every setBusyPulse call, and the test verifies the controller starts the pulse on entering
 * MIC_BUSY, stops it on leaving, and does not restart it when the state does not change.
 */
class MicBusyPulseTest {

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
            window.busyPulseCalls.clear()
            window.busyPulseOn = null
            window.calls.clear()
            log.clear()
        }
    }

    /** A failure means setState(MIC_BUSY) does not start the busy pulse. */
    @Test
    fun `setState MIC_BUSY starts the busy pulse`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.MIC_BUSY)
        assertEquals(
            "overlay: setState(MIC_BUSY) should call setBusyPulse(true) once",
            listOf(true),
            rig.window.busyPulseCalls,
        )
        assertEquals(
            "overlay: the last setBusyPulse call should be true",
            true,
            rig.window.busyPulseOn,
        )
    }

    /** A failure means setState(non-MIC_BUSY) does not stop the busy pulse. */
    @Test
    fun `setState non-MIC_BUSY stops the busy pulse`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.MIC_BUSY)
        rig.forget()
        rig.controller.setState(TileState.IDLE)
        assertEquals(
            "overlay: setState(IDLE) should call setBusyPulse(false)",
            listOf(false),
            rig.window.busyPulseCalls,
        )
        assertEquals(
            "overlay: the last setBusyPulse call should be false",
            false,
            rig.window.busyPulseOn,
        )
    }

    /** A failure means setState(MIC_BUSY) twice restarts the pulse. */
    @Test
    fun `setState MIC_BUSY when already MIC_BUSY does not restart pulse`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.MIC_BUSY)
        rig.forget()
        rig.controller.setState(TileState.MIC_BUSY)
        assertEquals(
            "overlay: a repeated setState(MIC_BUSY) should not call setBusyPulse again",
            0,
            rig.window.busyPulseCallCount,
        )
    }

    /** A failure means the busy pulse is not stopped when the face changes away from MIC_BUSY. */
    @Test
    fun `busy pulse stops when face changes away from MIC_BUSY`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.MIC_BUSY)
        rig.forget()
        rig.controller.setState(TileState.RECORDING)
        assertEquals(
            "overlay: setState(RECORDING) should call setBusyPulse(false)",
            listOf(false),
            rig.window.busyPulseCalls,
        )
        assertEquals(
            "overlay: the last setBusyPulse call should be false",
            false,
            rig.window.busyPulseOn,
        )
    }

    /** A failure means the busy pulse is not started when MIC_BUSY is pushed while hidden and then shown. */
    @Test
    fun `MIC_BUSY pushed while hidden starts the busy pulse on show`() {
        val rig = Rig()
        rig.controller.setState(TileState.MIC_BUSY)
        assertTrue(
            "overlay: a push while hidden should ask the window for nothing",
            rig.window.busyPulseCalls.isEmpty(),
        )
        assertEquals("overlay: show should report SHOWN", ShowResult.SHOWN, rig.controller.show())
        assertEquals(
            "overlay: show should call setBusyPulse(true)",
            listOf(true),
            rig.window.busyPulseCalls,
        )
    }

    /** A failure means hiding the tile leaves the busy pulse on. */
    @Test
    fun `hide stops the busy pulse`() {
        val rig = Rig().shown()
        rig.controller.setState(TileState.MIC_BUSY)
        rig.forget()
        rig.controller.hide()
        assertEquals(
            "overlay: hide should call setBusyPulse(false)",
            listOf(false),
            rig.window.busyPulseCalls,
        )
    }
}
