package dev.breaker.dictation.wiring

import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Turns a frame of 16 kHz mono samples into a level from 0 to 1 for the level bar.
 *
 * The level is the RMS of the frame in dBFS, mapped linearly onto a fixed range: [FLOOR_DBFS] gives
 * 0 and [CEILING_DBFS] gives 1, and anything beyond the range is held to the nearer end. The range is
 * fixed rather than adaptive, so a quiet room reads low and normal speech reads high.
 */
internal object LevelMap {
    /** The level is 0 at this many dBFS and below. */
    const val FLOOR_DBFS: Float = -60f

    /** The level is 1 at this many dBFS and above. */
    const val CEILING_DBFS: Float = -6f

    /**
     * The level of [samples], from 0 to 1. An empty frame, a silent frame, a NaN sample and an
     * infinite sample all give 0, so a bad value never lights the bar.
     */
    fun levelOf(samples: FloatArray): Float {
        if (samples.isEmpty()) return 0f
        var sum = 0.0
        for (sample in samples) {
            if (!sample.isFinite()) return 0f
            sum += sample.toDouble() * sample.toDouble()
        }
        val rms = sqrt(sum / samples.size)
        if (rms <= 0.0) return 0f
        val dbfs = (20.0 * log10(rms)).toFloat()
        return ((dbfs - FLOOR_DBFS) / (CEILING_DBFS - FLOOR_DBFS)).coerceIn(0f, 1f)
    }
}
