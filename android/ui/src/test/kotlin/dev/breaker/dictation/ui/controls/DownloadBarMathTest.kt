package dev.breaker.dictation.ui.controls

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val TOLERANCE = 0.001f

class DownloadBarMathTest {

    private fun near(expected: Float, actual: Float) =
        assertEquals("expected $expected, got $actual", expected, actual, TOLERANCE)

    @Test
    fun `a fill of zero of a hundred is empty`() {
        assertEquals(0f, DownloadBarMath.fillFraction(0, 100, false), 0f)
    }

    @Test
    fun `a fill of fifty of a hundred is one half`() {
        assertEquals(0.5f, DownloadBarMath.fillFraction(50, 100, false), 0f)
    }

    @Test
    fun `a fill of all of a hundred is the whole track`() {
        assertEquals(1f, DownloadBarMath.fillFraction(100, 100, false), 0f)
    }

    @Test
    fun `a fill above the total is held at the whole track`() {
        assertEquals(1f, DownloadBarMath.fillFraction(150, 100, false), 0f)
    }

    @Test
    fun `a negative amount done counts as nothing`() {
        assertEquals(0f, DownloadBarMath.fillFraction(-5, 100, false), 0f)
    }

    @Test
    fun `an unknown total gives a full track`() {
        assertEquals(1f, DownloadBarMath.fillFraction(7, 0, false), 0f)
    }

    @Test
    fun `the checking state gives a full track whatever the numbers`() {
        assertEquals(1f, DownloadBarMath.fillFraction(3, 100, true), 0f)
        assertEquals(1f, DownloadBarMath.fillFraction(0, 0, true), 0f)
    }

    @Test
    fun `the bar uses the megabyte numbers it is given without rounding them again`() {
        assertEquals(1, DownloadBarMath.percent(7, 365))
        assertEquals(7f / 365f, DownloadBarMath.fillFraction(7, 365, false), 0f)
    }

    @Test
    fun `an unknown total has no percent`() {
        assertNull(DownloadBarMath.percent(7, 0))
        assertNull(DownloadBarMath.percent(7, -1))
    }

    @Test
    fun `the percent takes the whole part of the fraction`() {
        assertEquals(99, DownloadBarMath.percent(199, 200))
        assertEquals(33, DownloadBarMath.percent(1, 3))
        assertEquals(66, DownloadBarMath.percent(2, 3))
    }

    @Test
    fun `a finished download reads 99 percent until the check starts`() {
        assertEquals(99, DownloadBarMath.percent(100, 100))
    }

    @Test
    fun `the percent is never below zero`() {
        assertEquals(0, DownloadBarMath.percent(-5, 100))
    }

    @Test
    fun `the percent is never above 99`() {
        assertEquals(99, DownloadBarMath.percent(150, 100))
    }

    @Test
    fun `a total of zero or less is indeterminate and a positive total is not`() {
        assertTrue(DownloadBarMath.isIndeterminate(0))
        assertTrue(DownloadBarMath.isIndeterminate(-1))
        assertTrue(!DownloadBarMath.isIndeterminate(1))
    }

    @Test
    fun `the stripes stand still when animations are off, however long the wait`() {
        assertEquals(0f, DownloadBarMath.stripeOffsetDp(1000L, false), 0f)
        assertEquals(0f, DownloadBarMath.stripeOffsetDp(3_600_000L, false), 0f)
    }

    @Test
    fun `the stripe offset is zero at elapsed time zero`() {
        assertEquals(0f, DownloadBarMath.stripeOffsetDp(0L, true), 0f)
    }

    @Test
    fun `one second moves the stripes 80 dp, modulo the period`() {
        near(11.9f, DownloadBarMath.stripeOffsetDp(1000L, true))
    }

    @Test
    fun `the stripe offset grows between two nearby times`() {
        val earlier = DownloadBarMath.stripeOffsetDp(1000L, true)
        val later = DownloadBarMath.stripeOffsetDp(1100L, true)
        assertTrue("expected $later above $earlier", later > earlier)
    }

    @Test
    fun `the stripe offset wraps back below the period and stays in range`() {
        near(1.1f, DownloadBarMath.stripeOffsetDp(2000L, true))
        for (elapsed in 0L..5000L step 37L) {
            val offset = DownloadBarMath.stripeOffsetDp(elapsed, true)
            assertTrue("offset $offset at $elapsed", offset >= 0f && offset < DownloadBarMath.STRIPE_PERIOD_DP)
        }
    }

    @Test
    fun `two fills at the same moment share one stripe phase`() {
        val offset = DownloadBarMath.stripeOffsetDp(1500L, true)
        val narrow = DownloadBarMath.stripeStarts(50f, offset)
        val wide = DownloadBarMath.stripeStarts(120f, offset)
        assertTrue("the narrow fill has no stripes", narrow.isNotEmpty())
        for (index in narrow.indices) {
            near(narrow[index], wide[index])
        }
    }

    @Test
    fun `no stripe starts on an empty or negative fill`() {
        assertTrue(DownloadBarMath.stripeStarts(0f, 5f).isEmpty())
        assertTrue(DownloadBarMath.stripeStarts(-3f, 5f).isEmpty())
    }

    @Test
    fun `the first stripe starts one period before the offset`() {
        near(-19.7f, DownloadBarMath.stripeStarts(100f, 3f).first())
    }

    @Test
    fun `the stripes are one period apart`() {
        val starts = DownloadBarMath.stripeStarts(100f, 5f)
        assertTrue("too few stripes: $starts", starts.size > 2)
        for (index in 1 until starts.size) {
            near(DownloadBarMath.STRIPE_PERIOD_DP, starts[index] - starts[index - 1])
        }
    }

    @Test
    fun `the last stripe starts left of the fill's right edge and the next one would not`() {
        val starts = DownloadBarMath.stripeStarts(100f, 0f)
        assertTrue("the last stripe is not left of the edge: ${starts.last()}", starts.last() < 100f)
        assertTrue("another stripe would have fitted", starts.last() + DownloadBarMath.STRIPE_PERIOD_DP >= 100f)
    }

    @Test
    fun `a fill of a hundred dp with no offset starts six stripes`() {
        assertEquals(6, DownloadBarMath.stripeStarts(100f, 0f).size)
    }

    @Test
    fun `the stripe and bar constants are the art's numbers`() {
        near(8f, DownloadBarMath.STRIPE_WIDTH_DP)
        near(14.7f, DownloadBarMath.STRIPE_GAP_DP)
        near(80f, DownloadBarMath.STRIPE_SPEED_DP_PER_S)
        near(24f, DownloadBarMath.BAR_HEIGHT_DP)
        near(22.7f, DownloadBarMath.STRIPE_PERIOD_DP)
    }
}
