package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.AppSettings
import dev.breaker.dictation.core.model.ShakeWakeMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The coordinator with a wake policy: a shake shows the tile only when the shake mode allows it, and a
 * keyboard reading raises the tile only when the keyboard switch is on. The rig is the one in TileFakes.kt.
 * The keyboard flag of a policy is set by the test on the policy object, as the keyboard wiring does.
 */
class WakePolicyCoordinatorTest {

    private fun shows(r: TileRig): Int = r.tile.calls.count { it == "show" }

    private fun hides(r: TileRig): Int = r.tile.calls.count { it == "hide" }

    @Test
    fun `off mode makes a shake with the tile hidden show nothing`() {
        val r = TileRig()
        r.coordinator.wake = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.OFF) }
        r.coordinator.onCloseRequested()
        r.tile.calls.clear()
        r.coordinator.onShake()
        r.settle()
        assertEquals("app: a shake in off mode must make no tile show call", 0, shows(r))
        assertFalse("app: the tile must stay hidden after a shake in off mode", r.tile.shown)
    }

    @Test
    fun `always mode shows a closed tile on a shake as before`() {
        val r = TileRig()
        r.coordinator.wake = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.ALWAYS) }
        r.coordinator.onCloseRequested()
        r.tile.calls.clear()
        r.coordinator.onShake()
        r.settle()
        assertEquals("app: a shake in always mode must show the tile once", 1, shows(r))
        assertTrue("app: the tile must be shown after a shake in always mode", r.tile.shown)
    }

    @Test
    fun `keyboard only shows a closed tile on a shake while a keyboard window is up`() {
        val r = TileRig()
        val policy = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.KEYBOARD_ONLY) }
        r.coordinator.wake = policy
        policy.onReading(true)
        r.coordinator.onCloseRequested()
        r.tile.calls.clear()
        r.coordinator.onShake()
        r.settle()
        assertEquals("app: a shake in keyboard only mode with the window up must show the tile once", 1, shows(r))
    }

    @Test
    fun `keyboard only refuses a shake when no keyboard window is up`() {
        val r = TileRig()
        val policy = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.KEYBOARD_ONLY) }
        r.coordinator.wake = policy
        policy.onReading(false)
        r.coordinator.onCloseRequested()
        r.tile.calls.clear()
        r.coordinator.onShake()
        r.settle()
        assertEquals("app: a shake in keyboard only mode with no window must make no show call", 0, shows(r))
    }

    @Test
    fun `keyboard only refuses a shake after the keyboard window has gone`() {
        val r = TileRig()
        val policy = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.KEYBOARD_ONLY) }
        r.coordinator.wake = policy
        policy.onReading(true)
        policy.onReading(false)
        r.coordinator.onCloseRequested()
        r.tile.calls.clear()
        r.coordinator.onShake()
        r.settle()
        assertEquals("app: a shake after the keyboard window has gone must make no show call", 0, shows(r))
    }

    @Test
    fun `keyboard only refuses a shake before any keyboard reading`() {
        val r = TileRig()
        r.coordinator.wake = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.KEYBOARD_ONLY) }
        r.coordinator.onCloseRequested()
        r.tile.calls.clear()
        r.coordinator.onShake()
        r.settle()
        assertEquals("app: a shake before any keyboard reading must make no show call", 0, shows(r))
    }

    @Test
    fun `keyboard only refuses a shake after the signal is lost`() {
        val r = TileRig()
        val policy = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.KEYBOARD_ONLY) }
        r.coordinator.wake = policy
        policy.onReading(true)
        policy.onSignalLost()
        r.coordinator.onCloseRequested()
        r.tile.calls.clear()
        r.coordinator.onShake()
        r.settle()
        assertEquals("app: a shake after the signal is lost must make no show call", 0, shows(r))
    }

    @Test
    fun `keyboard switch off stops a keyboard reading from raising the tile`() {
        val r = TileRig()
        r.coordinator.wake = WakePolicy { AppSettings(showTileOnKeyboard = false) }
        r.coordinator.onCloseRequested()
        r.tile.calls.clear()
        r.coordinator.onKeyboardReading(true, true, 1000L)
        r.settle()
        assertEquals("app: a keyboard reading with the switch off must make no show call", 0, shows(r))
        assertFalse("app: the tile must stay hidden when the keyboard switch is off", r.tile.shown)
    }

    @Test
    fun `keyboard switch off leaves a shake in always mode able to show the tile`() {
        val r = TileRig()
        r.coordinator.wake = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.ALWAYS, showTileOnKeyboard = false) }
        r.coordinator.onCloseRequested()
        r.tile.calls.clear()
        r.coordinator.onShake()
        r.settle()
        assertEquals("app: a shake in always mode must show the tile with the keyboard switch off", 1, shows(r))
    }

    @Test
    fun `shake off with the keyboard switch on still lets a keyboard reading raise the tile`() {
        val r = TileRig()
        r.coordinator.wake = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.OFF, showTileOnKeyboard = true) }
        r.coordinator.onCloseRequested()
        r.tile.calls.clear()
        r.coordinator.onKeyboardReading(true, true, 1000L)
        r.settle()
        assertEquals("app: a keyboard reading must raise the tile when the shake is off and the switch is on", 1, shows(r))
        assertTrue("app: the tile must be shown after the keyboard raise", r.tile.shown)
    }

    @Test
    fun `both off means neither a shake nor a keyboard reading shows the tile`() {
        val r = TileRig()
        r.coordinator.wake = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.OFF, showTileOnKeyboard = false) }
        r.coordinator.onCloseRequested()
        r.tile.calls.clear()
        r.coordinator.onShake()
        r.coordinator.onKeyboardReading(true, true, 1000L)
        r.settle()
        assertEquals("app: with both answers off no show call may be made", 0, shows(r))
    }

    @Test
    fun `keyboard switch on lets a keyboard reading raise the tile`() {
        val r = TileRig()
        r.coordinator.wake = WakePolicy { AppSettings(shakeWakeMode = ShakeWakeMode.ALWAYS, showTileOnKeyboard = true) }
        r.coordinator.onCloseRequested()
        r.tile.calls.clear()
        r.coordinator.onKeyboardReading(true, true, 1000L)
        r.settle()
        assertEquals("app: a keyboard reading with the switch on must raise the tile once", 1, shows(r))
    }

    @Test
    fun `a settings change between two shakes is honoured by the second shake`() {
        val r = TileRig()
        var first = true
        r.coordinator.wake = WakePolicy {
            if (first) {
                first = false
                AppSettings(shakeWakeMode = ShakeWakeMode.OFF)
            } else {
                AppSettings(shakeWakeMode = ShakeWakeMode.ALWAYS)
            }
        }
        r.coordinator.onCloseRequested()
        r.tile.calls.clear()
        r.coordinator.onShake()
        r.settle()
        assertEquals("app: the first shake in off mode must make no show call", 0, shows(r))
        r.coordinator.onShake()
        r.settle()
        assertEquals("app: the second shake, after the mode became always, must show the tile", 1, shows(r))
    }

    @Test
    fun `a shake while a take is in flight reads no settings`() {
        val r = TileRig()
        var reads = 0
        r.coordinator.wake = WakePolicy {
            reads += 1
            AppSettings(shakeWakeMode = ShakeWakeMode.ALWAYS)
        }
        r.startRecording()
        reads = 0
        r.coordinator.onShake()
        r.settle()
        assertEquals("app: a shake while a take is in flight must read no settings", 0, reads)
        assertEquals("app: a shake while a take is in flight must begin no take", 1, r.take.beginCalls)
    }

    @Test
    fun `the default policy keeps keyboard show and hide as before`() {
        val r = TileRig(arm = false)
        r.tile.showResult = TileShow.NO_PERMISSION
        r.coordinator.onArmedChanged(true)
        r.tile.showResult = TileShow.SHOWN
        r.tile.calls.clear()
        r.coordinator.onKeyboardReading(true, true, 1000L)
        r.settle()
        assertTrue("app: the default policy must let a keyboard reading raise the tile", r.tile.shown)
        r.coordinator.onKeyboardReading(false, false, 1500L)
        r.settle()
        assertFalse("app: the default policy must let a keyboard gone reading take the tile down", r.tile.shown)
        assertEquals("app: one hide call for the keyboard gone reading", 1, hides(r))
    }
}
