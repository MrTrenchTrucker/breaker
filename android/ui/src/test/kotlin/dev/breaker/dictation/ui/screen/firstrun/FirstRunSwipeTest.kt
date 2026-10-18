package dev.breaker.dictation.ui.screen.firstrun

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The swipe rule: a right-to-left drag that is long enough and mostly sideways moves the page on; every
 * other drag does not. The boundaries are checked at their exact values and one step past them.
 */
class FirstRunSwipeTest {
    @Test
    fun `the constants have the design values`() {
        assertTrue("ui: first run: MIN_DISTANCE_DP is not 64", FirstRunSwipe.MIN_DISTANCE_DP == 64f)
        assertTrue("ui: first run: HORIZONTAL_RATIO is not 2", FirstRunSwipe.HORIZONTAL_RATIO == 2f)
    }

    @Test
    fun `a clear right to left drag advances`() {
        assertTrue("ui: first run: a clear leftward drag did not advance", FirstRunSwipe.isAdvance(-200f, 0f))
    }

    @Test
    fun `exactly the minimum distance advances and one unit less does not`() {
        val minimum = FirstRunSwipe.MIN_DISTANCE_DP
        assertTrue("ui: first run: exactly the minimum distance did not advance", FirstRunSwipe.isAdvance(-minimum, 0f))
        assertFalse("ui: first run: one unit under the minimum distance advanced", FirstRunSwipe.isAdvance(-(minimum - 1f), 0f))
    }

    @Test
    fun `a left to right drag of the same length does not advance`() {
        assertFalse("ui: first run: a rightward drag of the minimum length advanced", FirstRunSwipe.isAdvance(FirstRunSwipe.MIN_DISTANCE_DP, 0f))
        assertFalse("ui: first run: a rightward drag of 200 dp advanced", FirstRunSwipe.isAdvance(200f, 0f))
    }

    @Test
    fun `a mostly vertical drag does not advance`() {
        assertFalse("ui: first run: a drag of 100 dp sideways and 100 dp down advanced", FirstRunSwipe.isAdvance(-100f, 100f))
        assertFalse("ui: first run: a drag of 64 dp sideways and 64 dp down advanced", FirstRunSwipe.isAdvance(-64f, 64f))
    }

    @Test
    fun `the ratio boundary advances and a little more vertical movement does not`() {
        val ratio = FirstRunSwipe.HORIZONTAL_RATIO
        assertTrue("ui: first run: a drag at exactly the ratio did not advance", FirstRunSwipe.isAdvance(-(ratio * 64f), 64f))
        assertFalse("ui: first run: a drag a little past the ratio advanced", FirstRunSwipe.isAdvance(-(ratio * 64f), 65f))
        assertTrue("ui: first run: the ratio boundary did not advance when the drag goes up", FirstRunSwipe.isAdvance(-(ratio * 64f), -64f))
    }

    @Test
    fun `a zero drag does not advance`() {
        assertFalse("ui: first run: a zero drag advanced", FirstRunSwipe.isAdvance(0f, 0f))
    }
}
