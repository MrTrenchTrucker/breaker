package dev.breaker.dictation.overlay

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Drop tests for the close target box: a collapsed tile dropped with its centre on the drawn box
 * closes through [CloseTarget.onDragEnd], and a drop with its centre outside the box saves, including
 * a centre in a corner that the arcs cut off. The screen is [TestData.SCREEN] and the tile is 100
 * pixels square, so the tile centre is (tileX + 50, tileY + 50). These tests run on a plain JVM.
 */
class CloseTargetDropTest {

    /** What one drop did: whether the tile closed, and whether the position was saved. */
    private class Outcome(val closed: Boolean, val saved: Boolean)

    /** Drops a collapsed tile with its centre at ([cx], [cy]) on a fresh window and reports the outcome. */
    private fun dropAt(cx: Int, cy: Int): Outcome {
        val window = FakeTileWindow()
        val half = window.tileSizePx() / 2
        var closed = false
        var saved = false
        CloseTarget.onDragEnd(window, true, cx - half, cy - half, { closed = true }, {}, { saved = true })
        return Outcome(closed, saved)
    }

    /** The target centre and the whole-pixel reach of the drawn box on the default screen. */
    private val window = FakeTileWindow()
    private val centre = CloseTarget.targetCentreInPixels(window.usableBounds())
    private val scale = CloseTarget.boxScale(window.usableBounds())
    private val reachX = CloseTargetShape.halfWidthPx(scale).toInt()
    private val reachY = CloseTargetShape.halfHeightPx(scale).toInt()

    @Test
    fun `a drop with its centre at the target centre closes the tile`() {
        val outcome = dropAt(centre.first, centre.second)
        assertTrue("overlay: a drop at the target centre should close the tile", outcome.closed)
        assertFalse("overlay: a drop that closes the tile should not save the position", outcome.saved)
    }

    @Test
    fun `a centre one pixel inside each drawn side closes the tile`() {
        val (cx, cy) = centre
        assertTrue("overlay: one pixel inside the right side closes", dropAt(cx + reachX, cy).closed)
        assertTrue("overlay: one pixel inside the left side closes", dropAt(cx - reachX, cy).closed)
        assertTrue("overlay: one pixel inside the top side closes", dropAt(cx, cy - reachY).closed)
        assertTrue("overlay: one pixel inside the bottom side closes", dropAt(cx, cy + reachY).closed)
    }

    @Test
    fun `a centre one pixel outside each drawn side saves the position`() {
        val (cx, cy) = centre
        assertTrue("overlay: one pixel outside the right side saves", dropAt(cx + reachX + 1, cy).saved)
        assertTrue("overlay: one pixel outside the left side saves", dropAt(cx - reachX - 1, cy).saved)
        assertTrue("overlay: one pixel outside the top side saves", dropAt(cx, cy - reachY - 1).saved)
        assertTrue("overlay: one pixel outside the bottom side saves", dropAt(cx, cy + reachY + 1).saved)
    }

    @Test
    fun `a centre in a cut corner saves the position`() {
        // (reachX, reachY - 1) lies inside the bounding box but outside the arc at that corner:
        // the arc centre is 16.9 pixels in from each side, and this point is about 22 pixels from it.
        val (cx, cy) = centre
        val dx = reachX
        val dy = reachY - 1
        assertFalse("overlay: the top right cut corner should not close", dropAt(cx + dx, cy - dy).closed)
        assertFalse("overlay: the top left cut corner should not close", dropAt(cx - dx, cy - dy).closed)
        assertFalse("overlay: the bottom right cut corner should not close", dropAt(cx + dx, cy + dy).closed)
        assertFalse("overlay: the bottom left cut corner should not close", dropAt(cx - dx, cy + dy).closed)
        assertTrue("overlay: a centre in a cut corner should save", dropAt(cx + dx, cy + dy).saved)
    }
}
