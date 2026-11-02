package dev.breaker.dictation.wiring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The wake switch reader reads the Wake Gesture switch live on every call and never caches. */
internal class WakeGestureGateTest {

    @Test
    fun `a shake may reach the coordinator while the switch is on`() {
        val enabled = true
        val wake = WakeGestureGate { enabled }
        assertTrue("app: mayShake must be true while enabled", wake.mayShake())
    }

    @Test
    fun `a shake is ignored while the switch is off`() {
        val enabled = false
        val wake = WakeGestureGate { enabled }
        assertFalse("app: mayShake must be false while disabled", wake.mayShake())
    }

    @Test
    fun `the switch is read fresh on every shake`() {
        var enabled = true
        var reads = 0
        val wake = WakeGestureGate { reads += 1; enabled }

        assertTrue("app: first shake with the switch on", wake.mayShake())
        enabled = false
        assertFalse("app: second shake after the switch was turned off", wake.mayShake())
        enabled = true
        assertTrue("app: third shake after the switch was turned on again", wake.mayShake())

        // The lambda runs once per call: three calls, three reads.
        assertEquals("app: the enabled lambda is invoked once per call", 3, reads)
    }

    @Test
    fun `a false reading is never cached so false then true reads true`() {
        var enabled = false
        val wake = WakeGestureGate { enabled }
        assertFalse("app: the first read is false", wake.mayShake())
        enabled = true
        assertTrue("app: the second read is true, not the cached false", wake.mayShake())
    }
}
