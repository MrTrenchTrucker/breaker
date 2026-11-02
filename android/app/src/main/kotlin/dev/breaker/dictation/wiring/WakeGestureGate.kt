package dev.breaker.dictation.wiring

/**
 * The shake obeys the Wake Gesture setting: a shake reaches the coordinator only while the
 * switch is on.
 *
 * [enabled] is read fresh on every call: nothing is cached, so flipping the switch while the
 * service stays armed takes effect on the very next shake, in both directions, without
 * restarting anything.
 *
 * Known limit: the sensor itself is started whenever the service is armed, so while the switch
 * is off it still runs and its shakes are ignored - a small battery cost, the same as before.
 */
internal class WakeGestureGate(private val enabled: () -> Boolean) {

    /** True while the Wake Gesture switch is on: a shake may reach the coordinator. Reads live. */
    fun mayShake(): Boolean = enabled()
}
