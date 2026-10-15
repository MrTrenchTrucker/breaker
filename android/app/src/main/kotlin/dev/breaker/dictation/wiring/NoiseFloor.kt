package dev.breaker.dictation.wiring

/**
 * The constants of the noise floor, in one place. After the seed, one update moves the floor by at
 * most the rate times [STEP_SAMPLES] of sample time.
 */
internal object FloorTuning {
    /** The seed lasts while the take has counted fewer samples than this. */
    const val SEED_SAMPLES: Int = 8000

    /** The most the floor falls per second of sample time, after the seed. */
    const val FALL_DB_PER_SECOND: Float = 30f

    /** The most the floor rises per second of sample time, after the seed. */
    const val RISE_DB_PER_SECOND: Float = 1f

    /** The zero point sits this far above the floor. */
    const val MARGIN_DB: Float = 8f

    /** The zero point is at most the ceiling minus this span. */
    const val MIN_SPAN_DB: Float = 12f

    /** The sample rate of the capture, in hertz. */
    const val SAMPLE_RATE_HZ: Int = 16_000

    /** The sample time of one update: the cadence of the level meter. */
    const val STEP_SAMPLES: Int = 1067
}

/**
 * The noise floor of one take: the level the room sits at, which the level bar is measured from.
 *
 * While the seed lasts, the floor is the lower median of the window levels, with no limit on how far it
 * moves. After the seed it follows each window, but falls at most [FloorTuning.FALL_DB_PER_SECOND] and
 * rises at most [FloorTuning.RISE_DB_PER_SECOND] per second of sample time. It is held at or above the
 * bottom of the bar. Silence is no information and does not move it.
 *
 * It has no thread, no clock and no lock. A new take makes a new floor.
 */
internal class NoiseFloor {
    /** The window levels of the seed, kept only while the seed lasts. */
    private val seedLevels: MutableList<Float> = mutableListOf()

    /** The fall of the floor in one update, in dB. */
    private val fallStepDb: Float =
        FloorTuning.FALL_DB_PER_SECOND * FloorTuning.STEP_SAMPLES / FloorTuning.SAMPLE_RATE_HZ

    /** The rise of the floor in one update, in dB. */
    private val riseStepDb: Float =
        FloorTuning.RISE_DB_PER_SECOND * FloorTuning.STEP_SAMPLES / FloorTuning.SAMPLE_RATE_HZ

    /** Samples counted through the latest update. */
    private var counted: Long = 0L

    /** The floor in dBFS, or null until the first window with a level. */
    private var floor: Float? = null

    /** The floor in dBFS, null until the first window with a level. */
    val floorDbfs: Float?
        get() = floor

    /**
     * The zero point of the bar: the floor plus [FloorTuning.MARGIN_DB], but at most the ceiling minus
     * [FloorTuning.MIN_SPAN_DB]. Null while the floor is null.
     */
    val zeroPointDbfs: Float?
        get() = floor?.let { minOf(it + FloorTuning.MARGIN_DB, LevelMap.CEILING_DBFS - FloorTuning.MIN_SPAN_DB) }

    /**
     * Takes one update: the window level [levelDbfs] (null = no information: digital silence or no valid frame)
     * and the [samples] that update covered (they advance the take's sample count either way).
     */
    fun update(levelDbfs: Float?, samples: Int) {
        counted += samples
        if (counted >= FloorTuning.SEED_SAMPLES) seedLevels.clear()
        if (levelDbfs == null) return
        val next = if (counted < FloorTuning.SEED_SAMPLES) seededFloor(levelDbfs) else movedFloor(levelDbfs)
        floor = maxOf(next, LevelMap.FLOOR_DBFS)
    }

    /** Adds a seed window and returns the lower median of the seed windows. */
    private fun seededFloor(levelDbfs: Float): Float {
        seedLevels.add(levelDbfs)
        return seedLevels.sorted()[(seedLevels.size - 1) / 2]
    }

    /** Moves a set floor toward a window level by at most one step; an unset floor takes the level. */
    private fun movedFloor(levelDbfs: Float): Float {
        val current = floor ?: return levelDbfs
        return if (levelDbfs < current) {
            maxOf(levelDbfs, current - fallStepDb)
        } else {
            minOf(levelDbfs, current + riseStepDb)
        }
    }
}
