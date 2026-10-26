package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The level glow's two pure functions, through [levelSmoothStep] and [levelAlpha]: the smoother that follows the
 * app's level, and the mapping of the smoothed level onto the glow's alpha.
 *
 * The attack factor is the larger of the two, so a rise moves further than a fall of the same size, and the glow
 * lifts quickly on a loud syllable and settles back gently. A non-finite value cannot be smoothed, so one is not
 * carried into the smoothed level. The alpha mapping is floored at [LEVEL_ALPHA_MIN] and reaches full strength at
 * the top, so a quiet room never dims the glow to a blink.
 */
class LevelPulseTest {

    private val nonFinite = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)

    /** The level the alpha mapping must not leave: a value whose alpha is below the floor or above full strength. */
    private fun alphaOutOfRange(alpha: Float): Boolean = alpha < LEVEL_ALPHA_MIN || alpha > PULSE_ALPHA_MAX

    /** A failure means a rise does not move further than a fall of the same size, so the glow neither lifts quickly on a loud syllable nor settles back gently. */
    @Test
    fun `a rise moves further than a fall of the same size`() {
        val gap = 0.2f
        val from = 0.3f
        val rise = levelSmoothStep(from, from + gap) - from
        val fall = levelSmoothStep(from + gap, from) - (from + gap)
        assertTrue("overlay: a rise of $gap from $from moved $rise, which is not further than the same fall moved $fall", rise > -fall)
        assertFalse("overlay: a rise must move further than a fall, not exactly the same", rise == -fall)
    }

    /** A failure means the rise is not the attack factor: the larger factor belongs to a rising level, or the factors are the same. */
    @Test
    fun `a rise moves by the attack factor`() {
        val from = 0.3f
        val rise = levelSmoothStep(from, from + 0.2f) - from
        assertEquals("overlay: a rise expected the attack factor $LEVEL_ATTACK", 0.2f * LEVEL_ATTACK, rise, 1e-6f)
        assertTrue("overlay: the attack factor must be the larger of the two", LEVEL_ATTACK > LEVEL_RELEASE)
    }

    /** A failure means the fall is not the release factor: the smaller factor belongs to a falling level, or the factors are the same. */
    @Test
    fun `a fall moves by the release factor`() {
        val from = 0.5f
        val fall = levelSmoothStep(from, from - 0.2f) - from
        assertEquals("overlay: a fall expected the release factor $LEVEL_RELEASE", -0.2f * LEVEL_RELEASE, fall, 1e-6f)
        assertTrue("overlay: the release factor must be the smaller of the two", LEVEL_RELEASE < LEVEL_ATTACK)
    }

    /** A failure means a level the same as the previous one is not held, so a steady syllable would show the glow wandering. */
    @Test
    fun `a level equal to the previous one is held`() {
        for (level in listOf(0f, 0.1f, 0.5f, 0.9f, 1f)) {
            assertEquals("overlay: $level equal to the previous one expected the same value", level, levelSmoothStep(level, level), 1e-6f)
        }
    }

    /** A failure means a non-finite level or a non-finite previous value is carried into the smoothed level instead of being shed. */
    @Test
    fun `a non finite level is not carried into the smoothed level`() {
        for (level in nonFinite) {
            assertEquals("overlay: a non-finite level $level expected the value itself", level, levelSmoothStep(0.4f, level), 0f)
        }
        for (previous in nonFinite) {
            assertEquals("overlay: a non-finite previous value $previous expected the level", 0.4f, levelSmoothStep(previous, 0.4f), 0f)
        }
        assertTrue("overlay: a NaN previous value and a NaN level expected the level", levelSmoothStep(Float.NaN, Float.NaN).isNaN())
    }

    /** A failure means a level outside 0 to 1 is held by the smoother, so the glow would follow a value the level scale does not have. */
    @Test
    fun `a level outside zero to one is not held by the smoother`() {
        assertEquals("overlay: a level below the scale expected the release step toward it", 0.4f + (-0.1f - 0.4f) * LEVEL_RELEASE, levelSmoothStep(0.4f, -0.1f), 1e-6f)
        assertEquals("overlay: a level above the scale expected the attack step toward it", 0.4f + (1.6f - 0.4f) * LEVEL_ATTACK, levelSmoothStep(0.4f, 1.6f), 1e-6f)
    }

    /** A failure means the alpha of a level is not held between the floor and full strength, so the glow could be drawn dimmer than the floor or brighter than the scale. */
    @Test
    fun `the alpha of a level is held between the floor and full strength`() {
        for (level in listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 1f, 1.7f)) {
            val alpha = levelAlpha(level)
            assertFalse("overlay: level $level expected an alpha within the floor and full strength, found $alpha", alphaOutOfRange(alpha))
        }
    }

    /** A failure means the alpha mapping has a corner, so the glow would change abruptly between neighbouring levels. */
    @Test
    fun `the alpha mapping is a straight line between the floor and full strength`() {
        for (level in listOf(0f, 0.1f, 0.25f, 0.5f, 0.75f, 0.9f)) {
            val expected = LEVEL_ALPHA_MIN + (PULSE_ALPHA_MAX - LEVEL_ALPHA_MIN) * level
            assertEquals("overlay: level $level expected the alpha of a straight line from the floor to full strength", expected, levelAlpha(level), 0f)
        }
    }

    /** A failure means the glow does not rest at its floor at silence or reach full strength at the top of the level scale. */
    @Test
    fun `the glow rests at the floor at silence and reaches full strength at the top`() {
        assertEquals("overlay: a smoothed level of 0 expected the level floor", LEVEL_ALPHA_MIN, levelAlpha(0f), 0f)
        assertEquals("overlay: a smoothed level of 1 expected full strength", PULSE_ALPHA_MAX, levelAlpha(1f), 0f)
    }

    /** A failure means the floor does not hold at or below the bottom of the scale, so a quiet room would dim the glow to a blink. */
    @Test
    fun `the floor holds at and below the bottom of the scale`() {
        for (level in listOf(0f, -0.1f, -0.5f)) {
            assertEquals("overlay: a smoothed level of $level expected the level floor", LEVEL_ALPHA_MIN, levelAlpha(level), 0f)
        }
    }

    /** A failure means the floor does not hold at the bottom edge of the scale, or full strength does not hold at the top edge. */
    @Test
    fun `the bottom and the top of the scale land exactly on the floor and on full strength`() {
        assertEquals("overlay: the bottom of the scale expected the floor, not one step off", LEVEL_ALPHA_MIN, levelAlpha(0f), 1e-6f)
        assertEquals("overlay: just below the top expected the alpha below full strength", LEVEL_ALPHA_MIN + (PULSE_ALPHA_MAX - LEVEL_ALPHA_MIN) * 0.9999f, levelAlpha(0.9999f), 1e-6f)
        assertEquals("overlay: full strength expected at the top and not one step above", PULSE_ALPHA_MAX, levelAlpha(1f), 0f)
    }

    /** A failure means a smoothed level that is not a finite number is not drawn at full strength. */
    @Test
    fun `a non finite smoothed level is drawn at full strength`() {
        for (level in nonFinite) {
            assertEquals("overlay: a smoothed level of $level expected full strength", PULSE_ALPHA_MAX, levelAlpha(level), 0f)
        }
    }

    /** A failure means a caller's own floor is ignored, so the glow does not rest where the caller asked. */
    @Test
    fun `a custom floor is honoured`() {
        assertEquals("overlay: a custom floor of 0.4 at a smoothed level of 0 expected 0.4", 0.4f, levelAlpha(0f, 0.4f), 1e-6f)
        assertEquals("overlay: a custom floor of 0.4 at the top expected full strength", PULSE_ALPHA_MAX, levelAlpha(1f, 0.4f), 0f)
        assertEquals("overlay: a custom floor of 0.4 expected the swing from 0.4, not from $LEVEL_ALPHA_MIN", 0.7f, levelAlpha(0.5f, 0.4f), 1e-6f)
    }

    /** A failure means the level glow and the busy ring's pulse do not share one floor, so the two glows read as different depths. */
    @Test
    fun `the level glow and the busy ring share one floor`() {
        assertEquals("overlay: the level floor expected the busy pulse floor $BUSY_PULSE_ALPHA_MIN", BUSY_PULSE_ALPHA_MIN, LEVEL_ALPHA_MIN, 0f)
    }
}
