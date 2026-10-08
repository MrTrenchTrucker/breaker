package dev.breaker.dictation.overlay

import kotlin.math.ceil

/**
 * Turns a sound level into the number of lit segments of the level meter.
 *
 * The mapping is linear: the level is multiplied by the number of segments and taken up to the next whole
 * number, so any sound at all lights the first segment, and a level of 1 or more lights every segment. Nothing
 * is smoothed and nothing decays; the same level always gives the same count.
 */
internal object LedMeter {
    /** How many segments the meter has. The design allows 12 to 16. */
    const val SEGMENTS: Int = 12

    /**
     * The number of lit segments, from 0 to [segments], for a [level] from 0.0 (silence) to 1.0 (full).
     *
     * A level that is not a number, zero or below gives 0. A level of 1 or more gives [segments].
     * Anything between is the level times [segments], taken up to the next whole number. With no segments (zero or fewer)
     * the answer is 0.
     */
    fun lit(level: Float, segments: Int = SEGMENTS): Int {
        if (segments <= 0) return 0
        if (level.isNaN() || level <= 0f) return 0
        if (level >= 1f) return segments
        return ceil(level * segments).toInt().coerceIn(0, segments)
    }
}
