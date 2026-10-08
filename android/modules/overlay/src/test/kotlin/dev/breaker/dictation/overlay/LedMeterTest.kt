package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.TruckingTokens
import kotlin.math.nextDown
import kotlin.math.nextUp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a sound level becomes a number of lit meter segments: silence, the smallest sound, the exact
 * segment boundaries, levels outside 0..1, values that are not numbers, other segment counts, and a sweep
 * that must never go down as the level goes up.
 */
class LedMeterTest {

    private fun lit(level: Float, segments: Int = LedMeter.SEGMENTS): Int = LedMeter.lit(level, segments)

    /** A failure means the meter has a segment count the design does not allow, or the default count is not the one used. */
    @Test
    fun `the meter has 12 segments, inside the range the design allows`() {
        assertEquals("overlay: the meter expected 12 segments", 12, LedMeter.SEGMENTS)
        assertTrue(
            "overlay: the segment count ${LedMeter.SEGMENTS} expected inside the design range ${TruckingTokens.ledBar.segmentRange}",
            LedMeter.SEGMENTS in TruckingTokens.ledBar.segmentRange,
        )
        assertEquals("overlay: the default segment count expected the meter's own count", lit(0.5f, 12), LedMeter.lit(0.5f))
        assertEquals("overlay: level 1 with the default count expected every segment lit", 12, LedMeter.lit(1f))
    }

    /** A failure means silence or a negative level lights a segment. */
    @Test
    fun `zero and negative levels light nothing`() {
        listOf(0f, -0f, -0.0001f, -0.5f, -1f, -100f, Float.NEGATIVE_INFINITY, -Float.MAX_VALUE).forEach { level ->
            assertEquals("overlay: level $level expected 0 lit segments", 0, lit(level))
        }
    }

    /** A failure means the smallest audible sound does not light the first segment, or lights more than one. */
    @Test
    fun `any positive sound lights the first segment`() {
        listOf(Float.MIN_VALUE, 1e-30f, 1e-6f, 0.001f, 0.0001f, 0.0833f).forEach { level ->
            assertEquals("overlay: level $level expected 1 lit segment", 1, lit(level))
        }
        assertEquals("overlay: the float just above 0 expected 1 lit segment", 1, lit(0f.nextUp()))
    }

    /** A failure means a level that is exactly a whole number of segments lights the next segment too, or one too few. */
    @Test
    fun `a level exactly on a segment boundary lights that many segments`() {
        for (k in 0..12) {
            val level = k / 12f
            assertEquals("overlay: level $k/12 expected $k lit segments of 12", k, lit(level))
        }
        for (k in 0..16) {
            val level = k / 16f
            assertEquals("overlay: level $k/16 expected $k lit segments of 16", k, lit(level, 16))
        }
    }

    /** A failure means a level just above a boundary does not light the next segment, or just below it lights it. */
    @Test
    fun `one step above a boundary lights one more and one step below lights the same`() {
        for (k in 0..15) {
            val boundary = k / 16f
            assertEquals("overlay: the float just above $k/16 expected ${k + 1} of 16", k + 1, lit(boundary.nextUp(), 16))
        }
        for (k in 1..16) {
            val boundary = k / 16f
            assertEquals("overlay: the float just below $k/16 expected $k of 16", k, lit(boundary.nextDown(), 16))
        }
        assertEquals("overlay: level 0.5 expected 6 of 12", 6, lit(0.5f))
        assertEquals("overlay: the float just above 0.5 expected 7 of 12", 7, lit(0.5f.nextUp()))
        assertEquals("overlay: the float just below 0.5 expected 6 of 12", 6, lit(0.5f.nextDown()))
    }

    /** A failure means the mapping is not a straight line from level to segments. */
    @Test
    fun `levels between the boundaries map linearly and go up to the next whole segment`() {
        val cases = listOf(0.0833f to 1, 0.084f to 2, 0.25f to 3, 0.26f to 4, 0.75f to 9, 0.76f to 10, 0.9f to 11, 0.92f to 12, 0.99f to 12, 0.9999f to 12)
        cases.forEach { (level, expected) ->
            assertEquals("overlay: level $level expected $expected of 12", expected, lit(level))
        }
    }

    /** A failure means a loud sound lights more segments than the meter has, or full level does not light them all. */
    @Test
    fun `a full level and anything above it light every segment`() {
        listOf(1f, 1.0001f, 1.5f, 2f, 1000f, Float.MAX_VALUE, Float.POSITIVE_INFINITY).forEach { level ->
            assertEquals("overlay: level $level expected all 12 segments", 12, lit(level))
            assertEquals("overlay: level $level expected all 16 segments", 16, lit(level, 16))
        }
    }

    /** A failure means a value that is not a number lights a segment or throws. */
    @Test
    fun `a level that is not a number lights nothing`() {
        assertEquals("overlay: NaN expected 0 lit segments of 12", 0, lit(Float.NaN))
        assertEquals("overlay: NaN expected 0 lit segments of 16", 0, lit(Float.NaN, 16))
        assertEquals("overlay: NaN expected 0 lit segments of 0", 0, lit(Float.NaN, 0))
    }

    /** A failure means another segment count is not honoured, or a count of zero or less lights something. */
    @Test
    fun `other segment counts, and none at all`() {
        assertEquals("overlay: level 0.5 of 16 segments expected 8", 8, lit(0.5f, 16))
        assertEquals("overlay: level 0.51 of 16 segments expected 9", 9, lit(0.51f, 16))
        assertEquals("overlay: level 0.5 of 1 segment expected 1", 1, lit(0.5f, 1))
        assertEquals("overlay: level 0 of 1 segment expected 0", 0, lit(0f, 1))
        assertEquals("overlay: level 1 of 1 segment expected 1", 1, lit(1f, 1))
        listOf(0, -1, -3, Int.MIN_VALUE).forEach { segments ->
            assertEquals("overlay: level 0.5 of $segments segments expected 0", 0, lit(0.5f, segments))
            assertEquals("overlay: level 1 of $segments segments expected 0", 0, lit(1f, segments))
            assertEquals("overlay: level 2 of $segments segments expected 0", 0, lit(2f, segments))
        }
    }

    /** A failure means a louder sound can light fewer segments than a quieter one, or the sweep leaves the 0..12 range or the ceiling rule. */
    @Test
    fun `over a sweep of a thousand levels the count never goes down`() {
        var previous = -1
        for (i in 0..1000) {
            val level = i / 1000f
            val count = lit(level)
            val expected = (i * 12 + 999) / 1000
            assertEquals("overlay: level $i/1000 expected $expected lit segments", expected, count)
            assertTrue("overlay: level $i/1000 gave $count, fewer than the $previous of the level before", count >= previous)
            assertTrue("overlay: level $i/1000 gave $count, outside 0..12", count in 0..12)
            previous = count
        }
        assertEquals("overlay: the sweep expected to start at 0", 0, lit(0f))
        assertEquals("overlay: the sweep expected to end at 12", 12, lit(1f))
    }
}
