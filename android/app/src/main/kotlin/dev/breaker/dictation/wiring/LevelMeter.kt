package dev.breaker.dictation.wiring

/**
 * The per-take state behind the level bar. It counts the samples a take delivers, takes the level of
 * each window of samples, and shows that level against the take's noise floor, with its fall limited
 * on each update.
 *
 * It has no thread, no clock and no lock. A take gets a meter of its own, and so a floor of its own;
 * nothing is shared between takes.
 */
internal class LevelMeter {
    /** Samples counted and not yet used by an update. */
    private var count: Int = 0

    /** Sum of squares of the finite samples in the window. */
    private var sumSquares: Double = 0.0

    /** Finite samples in the window: the count that [sumSquares] is taken over. */
    private var energySamples: Int = 0

    /** Samples in the window, finite or not: the sample time given to the floor at the next update. */
    private var sinceUpdate: Int = 0

    /** The noise floor of this take. */
    private val noiseFloor: NoiseFloor = NoiseFloor()

    /** The value shown after the last update. */
    private var shown: Float = 0f

    /**
     * Counts [samples] and returns the value to show when a window of [SAMPLES_PER_UPDATE] samples has
     * filled, or null when it has not. An empty frame returns null and counts nothing.
     */
    fun accept(samples: FloatArray): Float? {
        if (samples.isEmpty()) return null
        addToWindow(samples)
        count += samples.size
        if (count < SAMPLES_PER_UPDATE) return null
        count -= SAMPLES_PER_UPDATE
        // A frame longer than two windows would leave more than one window's worth here. Only the part
        // past the last whole window is kept, so one frame gives at most one update.
        if (count >= SAMPLES_PER_UPDATE) count %= SAMPLES_PER_UPDATE
        return takeUpdate()
    }

    /** Adds [samples] to the window sums when they are all finite, and counts them toward the sample time. */
    private fun addToWindow(samples: FloatArray) {
        val squares = LevelMap.sumSquaresOf(samples)
        if (squares != null) {
            sumSquares += squares
            energySamples += samples.size
        }
        sinceUpdate += samples.size
    }

    /** Takes one update from the window: its level moves the floor, and the shown value follows the level. */
    private fun takeUpdate(): Float {
        val level = LevelMap.dbfsOf(sumSquares, energySamples)
        noiseFloor.update(level, sinceUpdate)
        val raw = LevelMap.levelOf(level, noiseFloor.zeroPointDbfs ?: LevelMap.FLOOR_DBFS)
        shown = if (raw >= shown) raw else maxOf(raw, shown - FALL_PER_UPDATE)
        sumSquares = 0.0
        energySamples = 0
        sinceUpdate = 0
        return shown
    }

    companion object {
        /** Samples in one update: 1067 samples at 16 kHz is about 15 updates a second. */
        const val SAMPLES_PER_UPDATE: Int = 1067

        /** The most the shown value may fall at one update: one of twelve equal steps of the bar. */
        const val FALL_PER_UPDATE: Float = 1f / 12f
    }
}
