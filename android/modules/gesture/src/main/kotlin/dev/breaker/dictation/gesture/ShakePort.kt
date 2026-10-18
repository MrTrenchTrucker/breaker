// android/modules/gesture - ShakePort
// Card: android/modules/gesture/AGENTS.md   Registry: modules.toml [module.android_gesture]
// Owns: the sensor seam the public handle talks to.
// Depends on: no Android types; the SensorManager-backed implementation lives in SensorManagerShakeSource.
package dev.breaker.dictation.gesture

/**
 * The minimal sensor seam a [ShakeHandle] talks to.
 *
 * Production code passes the [android.hardware.SensorManager]-backed implementation
 * ([SensorManagerShakeSource]); tests pass a fake. The contract is one registration
 * at a time: [stop] removes the listener that [start] registered, and [stop] before
 * any [start] is a no-op.
 */
interface ShakePort {
    /**
     * Register the sensor and begin delivering readings via [onSample]. The sample
     * timestamps are in milliseconds. Returns whether a listener is actually
     * registered; false means the sensor could not be registered (for example, the
     * device has no accelerometer) and no readings will arrive.
     */
    fun start(onSample: (Float, Float, Float, Long) -> Unit): Boolean

    /** Remove the listener that [start] registered. Safe to call before any [start]. */
    fun stop()
}
