package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.WakeGestureMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The 3-way wake-gesture gate: mode read fresh, keyboard consulted only for KEYBOARD_ONLY. */
internal class WakeGestureGateTest {

    @Test
    fun `ALWAYS may shake without keyboard up`() {
        var keyboardUp = false
        var keyboardReads = 0
        val wake = WakeGestureGate(
            mode = { WakeGestureMode.ALWAYS },
            keyboardUp = { keyboardReads += 1; keyboardUp },
        )
        assertTrue("ALWAYS may shake without keyboard up", wake.mayShake())
        assertEquals("keyboard not consulted for ALWAYS", 0, keyboardReads)
    }

    @Test
    fun `OFF never shakes even with keyboard up`() {
        var keyboardUp = true
        val wake = WakeGestureGate(
            mode = { WakeGestureMode.OFF },
            keyboardUp = { keyboardUp },
        )
        assertFalse("OFF never shakes", wake.mayShake())
    }

    @Test
    fun `KEYBOARD_ONLY shakes only while keyboard up`() {
        var keyboardUp = false
        val wake = WakeGestureGate(
            mode = { WakeGestureMode.KEYBOARD_ONLY },
            keyboardUp = { keyboardUp },
        )
        assertFalse("KEYBOARD_ONLY false when keyboard down", wake.mayShake())
        keyboardUp = true
        assertTrue("KEYBOARD_ONLY true when keyboard up", wake.mayShake())
    }

    @Test
    fun `mode read fresh on every shake, no caching`() {
        var mode = WakeGestureMode.KEYBOARD_ONLY
        var keyboardUp = false
        var modeReads = 0
        val wake = WakeGestureGate(
            mode = { modeReads += 1; mode },
            keyboardUp = { keyboardUp },
        )
        assertFalse("first shake: KEYBOARD_ONLY, keyboard down", wake.mayShake())
        mode = WakeGestureMode.ALWAYS
        assertTrue("second shake: flipped to ALWAYS", wake.mayShake())
        mode = WakeGestureMode.OFF
        assertFalse("third shake: flipped to OFF", wake.mayShake())
        assertEquals("mode lambda invoked once per call", 3, modeReads)
    }

    @Test
    fun `KEYBOARD_ONLY reads keyboard fresh every call`() {
        var keyboardUp = false
        var keyboardReads = 0
        val wake = WakeGestureGate(
            mode = { WakeGestureMode.KEYBOARD_ONLY },
            keyboardUp = { keyboardReads += 1; keyboardUp },
        )
        assertFalse("keyboard down", wake.mayShake())
        keyboardUp = true
        assertTrue("keyboard up", wake.mayShake())
        keyboardUp = false
        assertFalse("keyboard down again", wake.mayShake())
        assertEquals("keyboard lambda invoked once per call", 3, keyboardReads)
    }
}
