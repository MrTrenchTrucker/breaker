package dev.breaker.dictation.wiring

import android.content.Context
import android.hardware.SensorManager
import android.media.AudioManager
import dev.breaker.dictation.audio.AndroidMicSource
import dev.breaker.dictation.audio.MicSource
import dev.breaker.dictation.commit.adapter.CommitServices
import dev.breaker.dictation.core.port.TextCommitter
import dev.breaker.dictation.gesture.SensorManagerShakeSource
import dev.breaker.dictation.gesture.ShakeHandle
import dev.breaker.dictation.gesture.ShakePort

/*
 * The swaps that need an Android type live here; the plain ones stay in Swaps.kt.
 */

/** The committer that puts dictated text into the focused field, made once per process from the application context. */
fun appTextCommitter(context: Context): TextCommitter = CommitServices.create(context)

/** The microphone the capture reads: the real one over the phone's recording inputs. */
fun appMicSource(audioManager: AudioManager): MicSource = AndroidMicSource.create(audioManager)

/**
 * The gesture that starts a dictation: a shake of the phone, read from the accelerometer through
 * the gesture module. Building it registers nothing; the sensor is registered only by
 * [ShakeGesture.start] and released by [ShakeGesture.stop]. Main thread only.
 */
fun appGesture(context: Context): GesturePort {
    val sensors: SensorManager = context.getSystemService(SensorManager::class.java) ?: return NoGesture()
    return ShakeGesture(SensorManagerShakeSource(sensors))
}

/**
 * Maps the app's [GesturePort] onto the gesture module's [ShakeHandle]. Nothing registers until
 * [start] is called. A start the port refuses leaves no trigger set, so the next start retries it.
 * The gesture module owns the sensor; this class holds only the trigger. Main thread only.
 */
class ShakeGesture(port: ShakePort) : GesturePort {
    private var trigger: (() -> Unit)? = null
    private val handle: ShakeHandle = ShakeHandle.create({ trigger?.invoke() }, port)

    override fun start(trigger: () -> Unit) {
        this.trigger = trigger
        if (!handle.start()) this.trigger = null
    }

    override fun stop() {
        trigger = null
        handle.stop()
    }
}
