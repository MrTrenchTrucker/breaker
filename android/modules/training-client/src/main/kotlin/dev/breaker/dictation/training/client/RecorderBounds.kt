package dev.breaker.dictation.training.client

import dev.breaker.dictation.core.port.AudioSource

/**
 * The length and loudness limits of one recorded phrase sample.
 *
 * All lengths are in samples at [AudioSource.SAMPLE_RATE_HZ] (16 kHz, mono). Written
 * but not compiled or run when this file was added.
 */
object RecorderBounds {
    /** 0.5 s: a phrase of a few syllables is not shorter than this, so less is a mis-tap. */
    const val MIN_SAMPLES: Int = 8_000

    /** 3.0 s: a spoken wake or send phrase is about 1 to 2 s, so longer holds speech that is not the phrase. */
    const val MAX_SAMPLES: Int = 48_000

    /**
     * 0.25 s of extra room kept in the buffer beyond [MAX_SAMPLES]: a clip that runs
     * slightly long is still held whole and answered as too long, and a source that never
     * stops cannot grow the buffer without bound.
     */
    const val BUFFER_MARGIN_SAMPLES: Int = 4_000

    /** Peak absolute amplitude (full scale is 1.0) below which a sample counts as silent; a peak of exactly this value is not silent. */
    const val SILENT_PEAK: Float = 0.02f
}

/** How many samples of each phrase a training run asks for. */
object SampleCounts {
    const val MIN_PER_PHRASE: Int = 10
    const val MAX_PER_PHRASE: Int = 20
    const val DEFAULT_PER_PHRASE: Int = 15
}
