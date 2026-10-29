package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.ShakeWakeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The answers of the wake policy: whether a shake may show the tile and whether a keyboard reading may
 * raise it. Each answer is read from the settings through the supplier the policy was built with, and
 * the keyboard flag is set only by the window readings and the lost signal the wiring passes in.
 */
class WakePolicyTest {

    private fun policy(mode: ShakeWakeMode, keyboardSwitch: Boolean = true): WakePolicy =
        WakePolicy { AppSettings(shakeWakeMode = mode, showTileOnKeyboard = keyboardSwitch) }

    @Test
    fun `always mode lets a shake show with the keyboard down`() {
        val p = policy(ShakeWakeMode.ALWAYS)
        assertTrue("app: always mode must let a shake show with no keyboard up", p.shakeMayShow())
    }

    @Test
    fun `always mode lets a shake show with the keyboard up`() {
        val p = policy(ShakeWakeMode.ALWAYS)
        p.onReading(true)
        assertTrue("app: always mode must let a shake show with the keyboard up", p.shakeMayShow())
    }

    @Test
    fun `keyboard only mode refuses a shake with the keyboard down`() {
        val p = policy(ShakeWakeMode.KEYBOARD_ONLY)
        p.onReading(false)
        assertFalse("app: keyboard only mode must refuse a shake with the keyboard down", p.shakeMayShow())
    }

    @Test
    fun `keyboard only mode lets a shake show with the keyboard up`() {
        val p = policy(ShakeWakeMode.KEYBOARD_ONLY)
        p.onReading(true)
        assertTrue("app: keyboard only mode must let a shake show with the keyboard up", p.shakeMayShow())
    }

    @Test
    fun `off mode refuses a shake with the keyboard down`() {
        val p = policy(ShakeWakeMode.OFF)
        assertFalse("app: off mode must refuse a shake with the keyboard down", p.shakeMayShow())
    }

    @Test
    fun `off mode refuses a shake with the keyboard up`() {
        val p = policy(ShakeWakeMode.OFF)
        p.onReading(true)
        assertFalse("app: off mode must refuse a shake with the keyboard up", p.shakeMayShow())
    }

    @Test
    fun `a fresh policy starts with the keyboard down so keyboard only refuses`() {
        val p = policy(ShakeWakeMode.KEYBOARD_ONLY)
        assertFalse("app: before any keyboard reading keyboard only must refuse a shake", p.shakeMayShow())
    }

    @Test
    fun `a window reading sets the keyboard flag that keyboard only reads`() {
        val p = policy(ShakeWakeMode.KEYBOARD_ONLY)
        p.onReading(true)
        assertTrue("app: a reading with the window up must set the keyboard flag", p.shakeMayShow())
    }

    @Test
    fun `a reading without the window clears the keyboard flag`() {
        val p = policy(ShakeWakeMode.KEYBOARD_ONLY)
        p.onReading(true)
        p.onReading(false)
        assertFalse("app: a reading without the window must clear the keyboard flag", p.shakeMayShow())
    }

    @Test
    fun `a lost signal clears the keyboard flag`() {
        val p = policy(ShakeWakeMode.KEYBOARD_ONLY)
        p.onReading(true)
        p.onSignalLost()
        assertFalse("app: a lost keyboard signal must clear the keyboard flag", p.shakeMayShow())
    }

    @Test
    fun `keyboard raise follows the keyboard switch`() {
        assertTrue("app: the keyboard switch on must allow a keyboard raise", policy(ShakeWakeMode.OFF, true).keyboardMayRaise())
        assertFalse("app: the keyboard switch off must refuse a keyboard raise", policy(ShakeWakeMode.ALWAYS, false).keyboardMayRaise())
    }

    @Test
    fun `the settings are read once per shake check`() {
        var reads = 0
        val p = WakePolicy {
            reads += 1
            AppSettings()
        }
        p.shakeMayShow()
        assertEquals("app: one shake check must read the settings exactly once", 1, reads)
    }

    @Test
    fun `the settings are read once per keyboard check`() {
        var reads = 0
        val p = WakePolicy {
            reads += 1
            AppSettings()
        }
        p.keyboardMayRaise()
        assertEquals("app: one keyboard check must read the settings exactly once", 1, reads)
    }

    @Test
    fun `a changed settings answer is seen by the next check`() {
        var mode: ShakeWakeMode = ShakeWakeMode.ALWAYS
        val p = WakePolicy { AppSettings(shakeWakeMode = mode) }
        assertTrue("app: the first check must see always mode", p.shakeMayShow())
        mode = ShakeWakeMode.OFF
        assertFalse("app: the next check must see the changed mode", p.shakeMayShow())
    }

    @Test
    fun `the permissive policy is always mode with the keyboard switch on`() {
        val p = WakePolicy.permissive()
        assertTrue("app: the permissive policy must let a shake show", p.shakeMayShow())
        assertTrue("app: the permissive policy must let a keyboard raise the tile", p.keyboardMayRaise())
    }
}
