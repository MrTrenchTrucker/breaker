package dev.breaker.dictation.wiring

import kotlin.math.log10

/**
 * Turns the samples of a frame into a level for the level bar.
 *
 * A frame is measured by its sum of squares, from which its level in dBFS follows. The bar maps that
 * level linearly from a zero point up to [CEILING_DBFS], which gives 1. The zero point is a parameter;
 * its default, [FLOOR_DBFS], is the fixed bottom of the bar. Levels beyond the range are held to the
 * nearer end.
 */
internal object LevelMap {
    /** The level is 0 at this many dBFS and below, when no zero point is given. */
    const val FLOOR_DBFS: Float = -60f

    /** The level is 1 at this many dBFS and above. */
    const val CEILING_DBFS: Float = -6f

    /** Sum of [samples] squared; null when any sample is NaN or infinite. An empty array gives 0.0. */
    fun sumSquaresOf(samples: FloatArray): Double? {
        var sum = 0.0
        for (sample in samples) {
            if (!sample.isFinite()) return null
            sum += sample.toDouble() * sample.toDouble()
        }
        return sum
    }

    /** 10 * log10(sumSquares / count) in dBFS (= 20 * log10 of the RMS); null when count <= 0 or sumSquares <= 0.0. */
    fun dbfsOf(sumSquares: Double, count: Int): Float? {
        if (count <= 0 || sumSquares <= 0.0) return null
        return (10.0 * log10(sumSquares / count)).toFloat()
    }

    /** The level 0..1 of [dbfs] above [zeroPointDbfs], up to CEILING_DBFS. Null (no information) gives 0f. */
    fun levelOf(dbfs: Float?, zeroPointDbfs: Float = FLOOR_DBFS): Float {
        if (dbfs == null) return 0f
        return ((dbfs - zeroPointDbfs) / (CEILING_DBFS - zeroPointDbfs)).coerceIn(0f, 1f)
    }
}
