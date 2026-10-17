// android/modules/gesture - ShakeHandle
// Card: android/modules/gesture/AGENTS.md   Registry: modules.toml [module.android_gesture]
// Owns: the public way in and out of the shake detector.
// Depends on: ShakePort, ShakeDetector; no Android types in this file.
package dev.breaker.dictation.gesture

/**
 * The public way in and out of the shake detector: a handle that owns a sensor
 * [ShakePort] and a [ShakeDetector] wired to [onShake].
 *
 * Start/stop contract (this class and every [ShakePort] it is given):
 * - call [start] and [stop] on the main thread only;
 * - the underlying listener is registered with no Handler, so sensor events arrive
 *   on the main looper; [onShake] and the detector's feed run there;
 * - this handle is not thread-safe beyond that.
 *
 * [start] is idempotent while started: a second [start] does not register a second
 * listener. A [start] the port refuses reports false and leaves the handle unstarted,
 * so a later [start] retries the registration. [stop] is safe before any [start] and
 * after an earlier [stop].
 */
class ShakeHandle internal constructor(
    private val port: ShakePort,
    private val detector: ShakeDetector,
) {
    private var started = false

    /**
     * Register the sensor and begin detecting. Returns whether the port registered a
     * listener: on false the handle stays unstarted, the detector stays stopped, and a
     * later [start] retries. A second call while started changes nothing and reports
     * true: the sensor is registered exactly once per start/stop pair.
     */
    fun start(): Boolean {
        if (started) return true
        val registered = port.start { x, y, z, t -> detector.feed(ShakeDetector.Sample(x, y, z, t)) }
        if (registered) {
            started = true
            detector.start()
        }
        return registered
    }

    /**
     * Unregister the sensor and stop accepting samples. The detector keeps its state,
     * so a later [start] resumes from where it left off.
     */
    fun stop() {
        if (!started) return
        started = false
        detector.stop()
        port.stop()
    }

    companion object {
        /**
         * The module's public factory: a handle over a sensor [port] whose detector
         * fires [onShake] per detected shake. Production code passes
         * [SensorManagerShakeSource]; tests pass a fake [ShakePort].
         */
        fun create(onShake: () -> Unit, port: ShakePort): ShakeHandle =
            ShakeHandle(port, ShakeDetector.create(onShake))
    }
}
