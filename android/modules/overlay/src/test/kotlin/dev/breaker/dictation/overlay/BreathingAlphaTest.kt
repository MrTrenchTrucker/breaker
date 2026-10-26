package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Home mic's breathing glow, through [breathingAlpha]: the alpha the glow is drawn at for one phase of a breath.
 *
 * The curve eases - a cosine - rather than the armed ring's straight-line triangle, so the glow leaves its floor
 * slowly, turns over at the peak and settles back slowly, and the motion has no corner in it. This class pins that
 * difference: at phase 0.125 the triangle gives 0.8125 and the cosine gives 0.7866117, so the two are told apart by a
 * value only the cosine produces. A non-finite phase is drawn at full strength, the phase wraps, and a caller's own
 * floor is honoured.
 */
class BreathingAlphaTest {

    private val nonFinite = listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)

    /** The value the armed ring's straight-line triangle gives at phase 0.125, which the cosine must not give. */
    private val triangleAtEighth = 0.8125f

    /** The value the cosine gives at phase 0.125: 0.75 + 0.25 * (1 - cos(PI/4)) / 2. */
    private val cosineAtEighth = 0.7866117f

    /** A failure means the glow does not rest at its floor at phase 0 and phase 1, which are the same moment. */
    @Test
    fun `the glow rests at the breathing floor at phase zero and phase one`() {
        assertEquals("overlay: phase 0 expected the breathing floor", BREATHING_ALPHA_MIN, breathingAlpha(0f), 0f)
        assertEquals("overlay: phase 1 expected the breathing floor", BREATHING_ALPHA_MIN, breathingAlpha(1f), 0f)
        assertEquals("overlay: the breathing floor expected 0.75", 0.75f, breathingAlpha(0f), 0f)
    }

    /** A failure means the glow does not reach full strength at the peak of the breath. */
    @Test
    fun `the glow reaches full strength at the peak of the breath`() {
        assertEquals("overlay: phase 0.5 expected full strength", PULSE_ALPHA_MAX, breathingAlpha(0.5f), 0f)
        assertEquals("overlay: the peak expected 1.0", 1f, breathingAlpha(0.5f), 0f)
    }

    /**
     * A failure means the phase the cycle is started at is not the peak, so the first frame the Home mic draws is
     * its floor and the glow fades in from a blink instead of beginning at full strength.
     */
    @Test
    fun `the phase the cycle starts at is the peak, so the first frame is full strength`() {
        assertEquals("overlay: the start phase expected the peak 0.5", 0.5f, BREATHING_PHASE_START, 0f)
        assertEquals("overlay: the start phase expected full strength", PULSE_ALPHA_MAX, breathingAlpha(BREATHING_PHASE_START), 0f)
    }

    /** A failure means the glow is not a breath: it must not jump, so the rise to the peak is non-decreasing and the fall from it is non-increasing. */
    @Test
    fun `the rise to the peak is non decreasing and the fall from it is non increasing`() {
        val steps = 20
        var previous = breathingAlpha(0f)
        for (i in 1..steps) {
            val phase = i.toFloat() / steps.toFloat()
            val alpha = breathingAlpha(phase)
            if (phase <= 0.5f) {
                assertTrue("overlay: the glow fell before the peak, at phase $phase: $previous then $alpha", alpha >= previous)
            } else {
                assertTrue("overlay: the glow rose after the peak, at phase $phase: $previous then $alpha", alpha <= previous)
            }
            previous = alpha
        }
    }

    /** A failure means the curve is the armed ring's triangle, which has a corner at the peak and at the floor and a breath must not. */
    @Test
    fun `the curve is the cosine ease and not the armed ring's triangle`() {
        val alpha = breathingAlpha(0.125f)
        assertEquals("overlay: phase 0.125 expected the cosine value $cosineAtEighth", cosineAtEighth, alpha, 1e-3f)
        assertNotEquals("overlay: phase 0.125 must not give the triangle's $triangleAtEighth", triangleAtEighth, alpha)
        assertTrue(
            "overlay: the cosine and the triangle must differ by more than the tolerance",
            kotlin.math.abs(alpha - triangleAtEighth) > 1e-3f,
        )
    }

    /** A failure means the glow is not symmetric about the peak, so the breath would not read the same in and out. */
    @Test
    fun `the glow is symmetric about the peak of the breath`() {
        for (d in listOf(0.1f, 0.25f, 0.4f)) {
            assertEquals(
                "overlay: phase ${0.5f - d} and ${0.5f + d} expected the same alpha",
                breathingAlpha(0.5f - d),
                breathingAlpha(0.5f + d),
                1e-6f,
            )
        }
    }

    /** A failure means a phase that is not a finite number is not drawn at full strength. */
    @Test
    fun `a non finite phase is drawn at full strength`() {
        for (phase in nonFinite) {
            assertEquals("overlay: phase $phase expected full strength", PULSE_ALPHA_MAX, breathingAlpha(phase), 0f)
        }
    }

    /** A failure means the phase does not wrap, so a breath that runs past one cycle would jump. */
    @Test
    fun `the phase wraps, so phase one point two five is phase zero point two five`() {
        assertEquals("overlay: phase 1.25 expected the same alpha as phase 0.25", breathingAlpha(0.25f), breathingAlpha(1.25f), 0f)
    }

    /** A failure means the caller's own floor is ignored, so the swing the caller asked for is not the one drawn. */
    @Test
    fun `a custom floor is honoured`() {
        assertEquals("overlay: a custom floor of 0.5 at phase 0 expected 0.5", 0.5f, breathingAlpha(0f, 0.5f), 0f)
        assertEquals("overlay: a custom floor of 0.5 at phase 0.5 expected full strength", PULSE_ALPHA_MAX, breathingAlpha(0.5f, 0.5f), 0f)
        assertEquals("overlay: a custom floor of 0.5 expected the swing from 0.5, not from 0.75", 0.5732233f, breathingAlpha(0.125f, 0.5f), 1e-3f)
    }
}
