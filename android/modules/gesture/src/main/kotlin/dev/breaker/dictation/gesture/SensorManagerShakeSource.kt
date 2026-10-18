// android/modules/gesture - SensorManagerShakeSource
// Card: android/modules/gesture/AGENTS.md   Registry: modules.toml [module.android_gesture]
// Owns: the SensorManager-backed half of the public handle door.
// Depends on: android.hardware.SensorManager; implements ShakePort, forwards samples to ShakeDetector.
package dev.breaker.dictation.gesture

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager

/**
 * A [ShakePort] backed by the device accelerometer, registered through a
 * supplied [SensorManager].
 *
 * This is NOT VERIFIED on a device: there is no instrumented test accompanying this adapter,
 * so the only claims that hold are structural - it registers the accelerometer listener
 * at [SENSOR_DELAY_GAME], converts the sensor's nanosecond timestamps to milliseconds and
 * forwards each sample's (x, y, z, timestamp) to the callback, and unregisters on [stop].
 * No tuning or behavioural outcome is claimed here. This file
 * is intentionally the only one in the module that imports android.*.
 *
 * The listener registers at `SensorManager.SENSOR_DELAY_GAME`, a polling budget of
 * 20,000 microseconds per sample = 50 samples per second; no permission is needed at
 * that rate - `HIGH_SAMPLING_RATE_SENSORS` applies only to rates above 200 Hz on API
 * 31 and up.
 *
 * [start] reports whether the registration happened: when the device has no
 * accelerometer it returns false without registering anything, so the handle
 * cannot believe it is listening.
 */
class SensorManagerShakeSource(
    private val sensorManager: SensorManager,
) : ShakePort {

    private var sensorEventListener: SensorEventListener? = null

    override fun start(onSample: (Float, Float, Float, Long) -> Unit): Boolean {
        // Capture the callback so this listener can forward to it.
        val callback = onSample
        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                callback.invoke(event.values[0], event.values[1], event.values[2],
                    ShakeDetector.toMillis(event.timestamp))
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) = Unit
        }
        val sensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (sensor == null) {
            // No accelerometer: report it instead of registering a dead listener.
            return false
        }
        val registered = sensorManager.registerListener(
            listener, sensor, SensorManager.SENSOR_DELAY_GAME, 0)
        if (registered) {
            sensorEventListener = listener
        }
        return registered
    }

    override fun stop() {
        val listener = sensorEventListener ?: return
        sensorManager.unregisterListener(listener)
        sensorEventListener = null
    }
}
