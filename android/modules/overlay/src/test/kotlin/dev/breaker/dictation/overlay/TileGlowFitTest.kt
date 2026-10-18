package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The glowing outline stays inside the 64 dp cell. The art box is drawn at ART_FILL of the cell side and
 * centred in the cell. The silhouette extents are the least and the greatest x and y of the numbers of the
 * three silhouette shapes, control points included, which is a safe superset of the curves. The outermost
 * glow layer reaches half its width past the silhouette on every side. Each literal margin below is worked
 * out by hand; the arithmetic is in the notes.
 */
class TileGlowFitTest {

    private val cell = 64f

    /** The dp per art unit of the box, as the geometry file gives it for the 64 dp cell: 64*0.90/1024 = 0.05625. */
    private fun scalePerUnit(): Float = ArtGeometry.frame(TileRect(0, 0, 64, 64)).scale

    /** Where art coordinate [v] lands along one axis of the cell, in dp: the geometry file's centre and box origin, scaled. */
    private fun dp(v: Float): Float {
        val frame = ArtGeometry.frame(TileRect(0, 0, 64, 64))
        return frame.centreX + (v + frame.originX) * frame.scale
    }

    /** The silhouette extents in art units, in this order: least x, greatest x, least y, greatest y. */
    private fun extents(): FloatArray {
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (shape in TileArt.silhouette) {
            for ((i, v) in shape.xy.withIndex()) {
                if (i % 2 == 0) {
                    minX = minOf(minX, v)
                    maxX = maxOf(maxX, v)
                } else {
                    minY = minOf(minY, v)
                    maxY = maxOf(maxY, v)
                }
            }
        }
        return floatArrayOf(minX, maxX, minY, maxY)
    }

    /** A failure means the silhouette numbers no longer span x 184 to 768 and y 40 to 960 in art units. */
    @Test
    fun `the silhouette spans x from 184 to 768 and y from 40 to 960 in art units`() {
        val e = extents()
        assertEquals("overlay: the least silhouette x expected 184", 184f, e[0], 0f)
        assertEquals("overlay: the greatest silhouette x expected 768", 768f, e[1], 0f)
        assertEquals("overlay: the least silhouette y expected 40", 40f, e[2], 0f)
        assertEquals("overlay: the greatest silhouette y expected 960", 960f, e[3], 0f)
    }

    /** A failure means the art box is not drawn at ART_FILL of the cell side, or the scale is not the cell side times ART_FILL over 1024. */
    @Test
    fun `the art box is drawn at ART_FILL of the cell side with its scale from that`() {
        assertTrue("overlay: ART_FILL expected above 0", ArtMetrics.ART_FILL > 0f)
        assertTrue("overlay: ART_FILL expected below 1", ArtMetrics.ART_FILL < 1f)
        assertEquals("overlay: ART_FILL expected 0.90", 0.90f, ArtMetrics.ART_FILL, 0f)
        assertEquals("overlay: the scale expected 0.05625 dp per art unit", 0.05625f, scalePerUnit(), 0.000001f)
        assertEquals("overlay: the box origin expected 3.2 dp", 3.2f, dp(0f), 0.0001f)
    }

    /** A failure means the outer glow layer is not 4 dp out from the silhouette on each side. */
    @Test
    fun `the outer glow layer is 4 dp out from the silhouette on each side`() {
        assertEquals("overlay: the glow layers expected 3", 3, ArtMetrics.GLOW_LAYERS)
        val halfWidth = ArtGeometry.glowWidthDp(ArtMetrics.GLOW_LAYERS - 1) / 2f
        assertEquals("overlay: the outer glow half width expected 4 dp", 4f, halfWidth, 0f)
    }

    /** A failure means the outer glow layer leaves the 64 dp cell on one edge, or an edge margin is not its hand-worked value. */
    @Test
    fun `the outer glow layer stays inside the 64 dp cell on all four edges`() {
        val e = extents()
        val halfWidth = ArtGeometry.glowWidthDp(ArtMetrics.GLOW_LAYERS - 1) / 2f
        val left = dp(e[0]) - halfWidth
        val right = dp(e[1]) + halfWidth
        val top = dp(e[2]) - halfWidth
        val bottom = dp(e[3]) + halfWidth
        assertTrue("overlay: the left edge of the glow expected at or after 0 dp", left >= 0f)
        assertTrue("overlay: the right edge of the glow expected at or before 64 dp", right <= cell)
        assertTrue("overlay: the top edge of the glow expected at or after 0 dp", top >= 0f)
        assertTrue("overlay: the bottom edge of the glow expected at or before 64 dp", bottom <= cell)
        // Margin arithmetic for the 64 dp cell (scale 0.05625 per art unit, origin -512): left = dp(184)-4 =
        // 9.55; right margin cell-right = 64 - (dp(768)+4) = 64 - 50.4 = 13.6; top = dp(40)-4 = 1.45; bottom
        // margin cell-bottom = 64 - (dp(960)+4) = 64 - 61.2 = 2.8. The half glow width 4 is unchanged (min side).
        assertEquals("overlay: the left margin expected 9.55 dp", 9.55f, left, 0.0001f)
        assertEquals("overlay: the right margin expected 13.6 dp", 13.6f, cell - right, 0.0001f)
        assertEquals("overlay: the top margin expected 1.45 dp", 1.45f, top, 0.0001f)
        assertEquals("overlay: the bottom margin expected 2.8 dp", 2.8f, cell - bottom, 0.0001f)
    }
}
