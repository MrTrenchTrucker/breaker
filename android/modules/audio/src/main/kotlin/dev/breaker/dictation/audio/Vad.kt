package dev.breaker.dictation.audio

import dev.breaker.dictation.core.model.AudioFormat

/**
 * Decides whether captured audio carries speech, and trims the silence around it.
 *
 * The card names two possible engines — a neural VAD or a lightweight energy
 * VAD — behind one interface, so the capture pipeline does not care which is
 * wired. [EnergyVad] is the one this module ships.
 */
internal interface Vad {
    /**
     * Whether one frame of 16 kHz mono PCM is speech.
     *
     * Used for a streaming decision (the wake path, a live level meter); it says
     * nothing about where speech started or ended.
     */
    fun isSpeech(frame: FloatArray): Boolean

    /**
     * Trim the leading and trailing silence from a whole capture.
     *
     * The result keeps the speech and drops the silence on both sides of it.
     * The direction matters more than anything else this module does: a trim
     * that keeps the tail and discards the head throws away the user's
     * dictation and keeps the room noise, and it looks like it worked.
     */
    fun trim(pcm: FloatArray): TrimResult
}

/**
 * What [Vad.trim] kept, and what it removed.
 *
 * Every duration is derived from the sample rate rather than counted separately,
 * so the reported milliseconds and the returned samples cannot disagree.
 */
internal class TrimResult(
    /** The retained audio: speech, plus the configured padding around it. */
    val pcm: FloatArray,
    /** Where the first speech frame began, measured from the start of the capture. */
    val speechStartMs: Long,
    /** Where the last speech frame ended, measured from the start of the capture. */
    val speechEndMs: Long,
    /** Silence removed before the first speech frame. */
    val leadingSilenceMs: Long,
    /** Silence removed after the last speech frame. */
    val trailingSilenceMs: Long,
    /** False when the capture held no speech at all and everything was removed. */
    val hasSpeech: Boolean,
) {
    /** Samples kept. */
    val sampleCount: Int get() = pcm.size

    /** Length of the retained audio in milliseconds. */
    val durationMs: Long get() = pcm.size * 1000L / AudioFormat.SAMPLE_RATE_HZ

    /** Never prints the audio: a transcript of this line would be a data leak. */
    override fun toString(): String =
        "TrimResult(samples=$sampleCount, speech=${speechStartMs}..${speechEndMs}ms, " +
            "trimmed=${leadingSilenceMs}ms leading, ${trailingSilenceMs}ms trailing, " +
            "hasSpeech=$hasSpeech)"
}
