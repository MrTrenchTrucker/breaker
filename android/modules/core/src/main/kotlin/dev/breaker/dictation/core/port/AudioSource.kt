package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.AudioFormat

/**
 * Hands the domain microphone audio.
 *
 * Implemented by the platform audio module. The source pushes
 * [FloatArray] frames of 16 kHz mono PCM to a listener; the domain never reads
 * a file, a device or a clock itself.
 */
interface AudioSource {
    /**
     * Start delivering frames of 16 kHz mono PCM to [listener]. Implementations
     * capture on their own thread and must not block the caller.
     */
    fun start(listener: AudioListener)

    /** Stop capturing. Safe to call when not capturing. */
    fun stop()

    companion object {
        /** The shape every frame has, restated so adapters do not have to. */
        const val SAMPLE_RATE_HZ: Int = AudioFormat.SAMPLE_RATE_HZ
    }
}

/**
 * Receives audio frames.
 *
 * [onFrame] is called on the capture thread with 16 kHz mono PCM. A frame that
 * is shorter than a single sample period means capture stopped, so the
 * implementation reports that rather than handing over a frame of silence.
 */
fun interface AudioListener {
    fun onFrame(samples: FloatArray)
}
