package dev.breaker.dictation.wiring

/**
 * The per-take state behind the level bar. It counts the samples a take delivers, takes the level of
 * the latest frame, and shows that level with its fall limited on each update.
 *
 * It has no thread, no clock and no lock. A take gets a meter of its own, so nothing is shared
 * between takes and nothing has to be reset from another thread.
 */
internal class LevelMeter {
    /** Samples counted and not yet used by an update. */
    private var count: Int = 0

    /** The level of the latest non-empty frame. */
    private var latest: Float = 0f

    /** The value shown after the last update. */
    private var shown: Float = 0f

    /**
     * Counts [samples] and returns the value to show when a window of [SAMPLES_PER_UPDATE] samples has
     * filled, or null when it has not. An empty frame returns null and counts nothing.
     */
    fun accept(samples: FloatArray): Float? {
        if (samples.isEmpty()) return null
        latest = LevelMap.levelOf(samples)
        count += samples.size
        if (count < SAMPLES_PER_UPDATE) return null
        count -= SAMPLES_PER_UPDATE
        // A frame longer than two windows would leave more than one window's worth here. Only the part
        // past the last whole window is kept, so one frame gives at most one update.
        if (count >= SAMPLES_PER_UPDATE) count %= SAMPLES_PER_UPDATE
        shown = if (latest >= shown) latest else maxOf(latest, shown - FALL_PER_UPDATE)
        return shown
    }

    companion object {
        /** Samples in one update: 1067 samples at 16 kHz is about 15 updates a second. */
        const val SAMPLES_PER_UPDATE: Int = 1067

        /** The most the shown value may fall at one update: one of twelve equal steps of the bar. */
        const val FALL_PER_UPDATE: Float = 1f / 12f
    }
}
