package dev.breaker.dictation.overlay

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

    /** A failure means the meter does not have six slots, or the default count is not the one used. The art test, not this file, counts the six slots of the grille. */
    @Test
    fun `the meter has six slots`() {
        assertEquals("overlay: the meter expected 6 slots", 6, LedMeter.SEGMENTS)
        assertEquals("overlay: level 0.5 with the default count expected 3 slots", 3, LedMeter.lit(0.5f))
        assertEquals("overlay: level 1 with the default count expected every slot lit", 6, LedMeter.lit(1f))
    }

    /** A failure means silence, the middle level or the full level lights another number of the six slots. */
    @Test
    fun `silence, the middle and full level light none, three and all six slots`() {
        assertEquals("overlay: level 0 expected 0 of 6", 0, lit(0f))
        assertEquals("overlay: level 0.5 expected 3 of 6", 3, lit(0.5f))
        assertEquals("overlay: level 1 expected 6 of 6", 6, lit(1f))
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

    /** A failure means a level above 0 does not light at least the first of the six slots, or the ceiling rule is lost. */
    @Test
    fun `a level above zero lights at least the first of the six slots`() {
        assertEquals("overlay: level 1e-6 expected 1 of 6", 1, lit(1e-6f, 6))
        assertEquals("overlay: the float just above 0 expected 1 of 6", 1, lit(0f.nextUp(), 6))
    }

    /** A failure means a level of one sixth does not light exactly the first of six slots, or the float just above it does not light the second. */
    @Test
    fun `a level of one sixth lights the first slot and the float above it lights the second`() {
        assertEquals("overlay: level 1/6 expected 1 of 6", 1, lit(1f / 6f, 6))
        assertEquals("overlay: the float above 1/6 expected 2 of 6", 2, lit((1f / 6f).nextUp(), 6))
    }

    /** A failure means a level that is exactly a whole number of slots lights one more or one fewer. */
    @Test
    fun `a level exactly on a segment boundary lights that many segments`() {
        for (k in 0..6) {
            val level = k / 6f
            assertEquals("overlay: level $k/6 expected $k lit segments of 6", k, lit(level, 6))
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
        assertEquals("overlay: level 0.5 expected 3 of 6", 3, lit(0.5f))
        assertEquals("overlay: the float just above 0.5 expected 4 of 6", 4, lit(0.5f.nextUp()))
        assertEquals("overlay: the float just below 0.5 expected 3 of 6", 3, lit(0.5f.nextDown()))
    }

    /** A failure means the mapping is not a straight line from level to segments. */
    @Test
    fun `levels between the boundaries map linearly and go up to the next whole segment`() {
        val cases = listOf(0.05f to 1, 0.17f to 2, 0.34f to 3, 0.4f to 3, 0.6f to 4, 0.67f to 5, 0.84f to 6, 0.99f to 6, 0.9999f to 6)
        cases.forEach { (level, expected) ->
            assertEquals("overlay: level $level expected $expected of 6", expected, lit(level))
        }
    }

    /** A failure means a loud sound lights more segments than the meter has, or full level does not light them all. */
    @Test
    fun `a full level and anything above it light every segment`() {
        listOf(1f, 1.0001f, 1.5f, 2f, 1000f, Float.MAX_VALUE, Float.POSITIVE_INFINITY).forEach { level ->
            assertEquals("overlay: level $level expected all 6 segments", 6, lit(level))
            assertEquals("overlay: level $level expected all 16 segments", 16, lit(level, 16))
        }
    }

    /** A failure means a value that is not a number lights a segment or throws. */
    @Test
    fun `a level that is not a number lights nothing`() {
        assertEquals("overlay: NaN expected 0 lit segments of 6", 0, lit(Float.NaN))
        assertEquals("overlay: NaN expected 0 lit segments of 16", 0, lit(Float.NaN, 16))
        assertEquals("overlay: NaN expected 0 lit segments of 0", 0, lit(Float.NaN, 0))
    }

    /** A failure means a value that is not a number lights one of the six slots. */
    @Test
    fun `a level that is not a number gives no lit slot of the six`() {
        assertEquals("overlay: NaN expected 0 of 6", 0, lit(Float.NaN, 6))
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

    /** A failure means a louder sound can light fewer segments than a quieter one, or the sweep leaves the 0..6 range or the ceiling rule. */
    @Test
    fun `over a sweep of a thousand levels the count never goes down`() {
        var previous = -1
        for (i in 0..1000) {
            val level = i / 1000f
            val count = lit(level)
            val expected = (i * 6 + 999) / 1000
            assertEquals("overlay: level $i/1000 expected $expected lit segments", expected, count)
            assertTrue("overlay: level $i/1000 gave $count, fewer than the $previous of the level before", count >= previous)
            assertTrue("overlay: level $i/1000 gave $count, outside 0..6", count in 0..6)
            previous = count
        }
        assertEquals("overlay: the sweep expected to start at 0", 0, lit(0f))
        assertEquals("overlay: the sweep expected to end at 6", 6, lit(1f))
    }

    /** A failure means a sweep from minus one to two gives more than six slots, or never lights all six. */
    @Test
    fun `over a sweep from minus one to two the count is never more than six and reaches six`() {
        var most = -1
        for (i in -100..200) {
            val count = lit(i / 100f)
            assertTrue("overlay: level ${i / 100f} gave $count, outside 0..6", count in 0..6)
            most = maxOf(most, count)
        }
        assertEquals("overlay: the largest count over the sweep expected 6", 6, most)
    }

    /** A failure means the count falls as the level rises somewhere in the sweep from minus one to two. */
    @Test
    fun `over a sweep from minus one to two the count never falls as the level rises`() {
        var previous = -1
        for (i in -100..200) {
            val count = lit(i / 100f)
            assertTrue("overlay: level ${i / 100f} gave $count, fewer than the $previous of the level before", count >= previous)
            previous = count
        }
    }
}
