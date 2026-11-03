package dev.breaker.dictation.wiring

import dev.breaker.dictation.core.model.WakeGestureMode

/**
 * The shake obeys the Wake Gesture setting: a shake reaches the coordinator only while the
 * mode permits it.
 *
 * [mode] is read fresh on every call: nothing is cached, so flipping the setting while the
 * service stays armed takes effect on the very next shake, in both directions, without
 * restarting anything.
 *
 * [keyboardUp] is consulted fresh for KEYBOARD_ONLY mode: a shake brings the tile back only
 * while the keyboard is up.
 *
 * Known limit: the sensor itself is started whenever the service is armed, so while the switch
 * is off it still runs and its shakes are ignored - a small battery cost, the same as before.
 */
internal class WakeGestureGate(
    private val mode: () -> WakeGestureMode,
    private val keyboardUp: () -> Boolean,
) {

    /** True while the Wake Gesture setting permits a shake to reach the coordinator. Reads live. */
    fun mayShake(): Boolean = when (mode()) {
        WakeGestureMode.ALWAYS -> true
        WakeGestureMode.KEYBOARD_ONLY -> keyboardUp()
        WakeGestureMode.OFF -> false
    }
}
