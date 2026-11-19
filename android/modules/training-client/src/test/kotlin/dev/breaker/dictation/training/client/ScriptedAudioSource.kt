package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.port.AudioListener
import dev.breaker.dictation.core.port.AudioSource
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * An [AudioSource] a test drives by hand: it counts start and stop calls, keeps every
 * listener it was given, delivers frames on the calling thread (or on a thread the test
 * makes), and can be told to throw from start or from stop.
 */
class ScriptedAudioSource : AudioSource {
    private val starts = AtomicInteger()
    private val stops = AtomicInteger()
    private val delivered = AtomicLong()
    private val given = CopyOnWriteArrayList<AudioListener>()

    @Volatile var failOnStart: Boolean = false
    @Volatile var failOnStop: Boolean = false

    /** Runs inside stop, after it is counted: lets a test play the capture thread. */
    @Volatile var onStop: (() -> Unit)? = null

    val startCalls: Int get() = starts.get()
    val stopCalls: Int get() = stops.get()

    /** Total samples handed to any listener, whether or not the recorder kept them. */
    val samplesDelivered: Long get() = delivered.get()

    override fun start(listener: AudioListener) {
        starts.incrementAndGet()
        if (failOnStart) throw IllegalStateException("scripted start failure")
        given.add(listener)
    }

    override fun stop() {
        stops.incrementAndGet()
        onStop?.invoke()
        if (failOnStop) throw IllegalStateException("scripted stop failure")
    }

    /** Hand a frame to the listener of the [index]th successful start (0 is the first). */
    fun deliverTo(index: Int, samples: FloatArray) {
        delivered.addAndGet(samples.size.toLong())
        given[index].onFrame(samples)
    }

    /** Hand a frame to the most recent listener. */
    fun deliver(samples: FloatArray) = deliverTo(given.size - 1, samples)

    /** Hand [total] samples of [value] to the most recent listener in frames of [frameSize]. */
    fun deliverSamples(total: Int, value: Float = 0.5f, frameSize: Int = 1_000) {
        var left = total
        while (left > 0) {
            val n = if (left < frameSize) left else frameSize
            deliver(FloatArray(n) { value })
            left -= n
        }
    }
}
