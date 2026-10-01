package dev.breaker.dictation.audio

/**
 * Removes steady background noise from captured audio.
 *
 * The card allows a neural suppressor when the app's native library is built
 * with one, and skipping suppression otherwise. This interface is the seam:
 * [PassThroughNoiseSuppressor] is the skip, and whatever is wired in its place
 * takes the same frames and returns the same number of samples.
 *
 * Implementations must be frame-rate agnostic in shape — the same instance sees
 * whatever frame size the capture hands over — and must return exactly as many
 * samples as they were given, because a suppressor that changes the length
 * silently shifts everything after it in the take.
 */
interface NoiseSuppressor {
    /**
     * True when this suppressor actually does something.
     *
     * The capture pipeline reports it so a take can say it was not suppressed,
     * rather than leaving a caller to guess from the audio.
     */
    val isActive: Boolean

    /**
     * Suppress [frame] in place-safe fashion and return the result.
     *
     * [frame] is 16 kHz mono float PCM. The returned array has the same length
     * as the input; the input is left untouched.
     */
    fun process(frame: FloatArray): FloatArray

    /** Forget everything learned about the noise floor, for the next capture. */
    fun reset()
}

/**
 * The suppressor that does nothing.
 *
 * This is what runs when the native library carries no neural suppressor. It is
 * a real object rather than a null check at the call site so the capture code
 * has one path, and so a take can report `isActive == false` instead of the
 * pipeline guessing whether it skipped a step.
 */
object PassThroughNoiseSuppressor : NoiseSuppressor {
    override val isActive: Boolean = false

    override fun process(frame: FloatArray): FloatArray = frame.copyOf()

    override fun reset() = Unit
}
