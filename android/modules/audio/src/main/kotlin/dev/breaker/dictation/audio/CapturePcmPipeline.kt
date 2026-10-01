package dev.breaker.dictation.audio

import dev.breaker.dictation.core.port.AudioSource

/**
 * Shorts to 16 kHz mono float: convert, downmix, resample, suppress.
 *
 * One of these belongs to a capture session and is used only by its capture
 * thread, because the resampler carries a read point between frames: a take
 * starts with it clean ([reset]) and ends by recovering what it was still
 * holding back ([drainTail]).
 */
internal class CapturePcmPipeline(
    channelCount: Int,
    sampleRateHz: Int,
    private val suppressor: NoiseSuppressor,
) {

    private val downmixer = ChannelDownmixer(channelCount)
    private val resampler = AudioResampler(
        fromSampleRateHz = sampleRateHz,
        toSampleRateHz = AudioSource.SAMPLE_RATE_HZ,
    )

    /** Drops everything the pipeline carries between frames of a take. */
    fun reset() {
        suppressor.reset()
        resampler.reset()
    }

    /** Shorts to 16 kHz mono float: convert, downmix, resample, suppress. */
    fun convert(buffer: ShortArray, count: Int): FloatArray {
        val interleaved = FloatArray(count) { buffer[it] / SHORT_SCALE }
        val mono = downmixer.downmix(interleaved)
        val resampled = resampler.resample(mono)
        return suppressor.process(resampled)
    }

    /**
     * The fraction of the last read the resampler was still holding back.
     *
     * Mid-capture those samples are one more read away; at the end of a take
     * they never arrive, so a take that ended without this came up short by
     * half a kernel — 15 samples at a 1:1 rate, which are 15 of the samples
     * the device produced and the listener was promised.
     */
    fun drainTail(): FloatArray = suppressor.process(resampler.drain())

    private companion object {
        private const val SHORT_SCALE = 32_768f
    }
}
