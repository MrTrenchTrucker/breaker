package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two recording buttons against the mic silhouette: the silhouette's edges and centre line, the buttons'
 * width and scale, the gap to the mic outline, the centring, the touch zones they sit in, the window's size,
 * and the art's box edges pinned to the path data. The literals come from the reference table at the tile
 * sizes 64, 168 and 192. Positions are compared to 0.01 pixel and the scale to 0.001.
 */
class TileControlPlacementTest {

    /** The reference values of one tile size, copied from the table. */
    private class Row(
        val zone: Int,
        val silLeft: Float,
        val silRight: Float,
        val silCentreY: Float,
        val ctrlWidth: Float,
        val ctrlScale: Float,
        val gap: Float,
    )

    private val pixel = 0.01f
    private val scale = 0.001f

    private val rows: List<Pair<Int, Row>> = listOf(
        64 to Row(zone = 48, silLeft = 55.55f, silRight = 88.4f, silCentreY = 31.325f, ctrlWidth = 29.565f, ctrlScale = 0.074659f, gap = 8f),
        168 to Row(zone = 126, silLeft = 145.5687f, silRight = 231.8f, silCentreY = 82.2281f, ctrlWidth = 77.6081f, ctrlScale = 0.195980f, gap = 21f),
        192 to Row(zone = 144, silLeft = 166.65f, silRight = 265.2f, silCentreY = 93.975f, ctrlWidth = 88.695f, ctrlScale = 0.223977f, gap = 24f),
    )

    /** The x numbers of every point of [shape], walking its ops: a move or a line has one point, a cubic three, a close none. */
    private fun xsOf(shape: ArtShape): List<Float> {
        val out = ArrayList<Float>()
        var k = 0
        for (op in shape.ops) {
            val points = pointsOf(op)
            for (p in 0 until points) {
                out.add(shape.xy[k + 2 * p])
            }
            k += 2 * points
        }
        return out
    }

    /** The number of points an op of the path carries: one for a move or a line, three for a cubic, none for a close. */
    private fun pointsOf(op: Int): Int = when (op) {
        TileArt.OP_MOVE -> 1
        TileArt.OP_LINE -> 1
        TileArt.OP_CUBIC -> 3
        else -> 0
    }

    private fun lowest(xs: List<Float>): Float = xs.reduce { a, b -> minOf(a, b) }

    private fun highest(xs: List<Float>): Float = xs.reduce { a, b -> maxOf(a, b) }

    /** A failure means the mic silhouette's left edge, right edge or centre line is not where the art puts it at 64, 168 or 192. */
    @Test
    fun `the silhouette edges and centre line sit where the art puts them`() {
        for ((s, r) in rows) {
            assertEquals("overlay: the silhouette left at $s expected ${r.silLeft}", r.silLeft, ControlLayout.silhouetteLeft(s), pixel)
            assertEquals("overlay: the silhouette right at $s expected ${r.silRight}", r.silRight, ControlLayout.silhouetteRight(s), pixel)
            assertEquals("overlay: the silhouette centre line at $s expected ${r.silCentreY}", r.silCentreY, ControlLayout.silhouetteCentreY(s), pixel)
        }
    }

    /** A failure means the X or the check is not ninety percent of the mic's full width, or its scale is not the one that makes it so. */
    @Test
    fun `the X and the check are ninety percent of the mic full width`() {
        for ((s, r) in rows) {
            val x = ControlLayout.cancel(s)
            val check = ControlLayout.send(s)
            assertEquals("overlay: the X width at $s expected ${r.ctrlWidth}", r.ctrlWidth, x.right - x.left, pixel)
            assertEquals("overlay: the check width at $s expected ${r.ctrlWidth}", r.ctrlWidth, check.right - check.left, pixel)
            assertEquals("overlay: the X scale at $s expected ${r.ctrlScale}", r.ctrlScale, x.scale, scale)
            assertEquals("overlay: the check scale at $s expected ${r.ctrlScale}", r.ctrlScale, check.scale, scale)
        }
    }

    /** A failure means the nearest edge of a button is not the design's eight dp, scaled to the tile, from the mic outline. */
    @Test
    fun `the nearest edge of each button is eight dp from the mic outline`() {
        for ((s, r) in rows) {
            assertEquals("overlay: the gap at $s expected ${r.gap}", r.gap, ControlLayout.gapPx(s), pixel)
            assertEquals("overlay: the X gap at $s expected ${r.gap}", r.gap, ControlLayout.silhouetteLeft(s) - ControlLayout.cancel(s).right, pixel)
            assertEquals("overlay: the check gap at $s expected ${r.gap}", r.gap, ControlLayout.send(s).left - ControlLayout.silhouetteRight(s), pixel)
        }
    }

    /** A failure means a button is not centred on the mic outline's centre line, top and bottom. */
    @Test
    fun `the buttons are centred on the mic outline centre line`() {
        for (s in listOf(64, 168, 192)) {
            val x = ControlLayout.cancel(s)
            val check = ControlLayout.send(s)
            val centre = ControlLayout.silhouetteCentreY(s)
            assertEquals("overlay: the X centre line at $s expected $centre", centre, (x.top + x.bottom) / 2f, pixel)
            assertEquals("overlay: the check centre line at $s expected $centre", centre, (check.top + check.bottom) / 2f, pixel)
        }
    }

    /** A failure means a button sticks out of its touch zone: the X must lie in the cancel zone, the check in the send zone, both inside the window's height. */
    @Test
    fun `each button sits inside its touch zone`() {
        for (s in listOf(64, 168, 192)) {
            val cancelZone = TileLayout.cancelCell(s)
            val sendZone = TileLayout.sendCell(s)
            val x = ControlLayout.cancel(s)
            val check = ControlLayout.send(s)
            assertTrue("overlay: the X at $s expected inside the cancel zone from its left edge", x.left >= cancelZone.left)
            assertTrue("overlay: the X at $s expected inside the cancel zone to its right edge", x.right <= cancelZone.right)
            assertTrue("overlay: the check at $s expected inside the send zone from its left edge", check.left >= sendZone.left)
            assertTrue("overlay: the check at $s expected inside the send zone to its right edge", check.right <= sendZone.right)
            assertTrue("overlay: the X at $s expected inside the window from its top edge", x.top >= 0f)
            assertTrue("overlay: the X at $s expected inside the window to its bottom edge", x.bottom <= s)
            assertTrue("overlay: the check at $s expected inside the window from its top edge", check.top >= 0f)
            assertTrue("overlay: the check at $s expected inside the window to its bottom edge", check.bottom <= s)
        }
    }

    /** A failure means the recording window is not three touch zones wide at its tile size, or a touch zone is under 47.5 dp. */
    @Test
    fun `the recording window is three zones wide and follows the tile size`() {
        for ((s, r) in rows) {
            assertEquals("overlay: the recording window at $s expected ${3 * r.zone} by $s", TileSize(3 * r.zone, s), TileLayout.windowSize(TileShape.RECORDING, s))
            val zones = listOf(TileLayout.cancelCell(s), TileLayout.micZone(TileShape.RECORDING, s), TileLayout.sendCell(s))
            zones.forEach { zone ->
                assertTrue("overlay: a touch zone at $s expected at least 47.5 dp, got ${zone.width} pixels", zone.width / (s / 64f) >= 47.5f)
            }
        }
    }

    /** A failure means the mic art cell does not centre the silhouette on the mic zone: their centres differ by more than half a pixel. */
    @Test
    fun `the mic art cell centres the outline on the mic zone`() {
        for ((s, _) in rows) {
            val outlineCentre = (ControlLayout.silhouetteLeft(s) + ControlLayout.silhouetteRight(s)) / 2f
            val zone = TileLayout.micZone(TileShape.RECORDING, s)
            val zoneCentre = (zone.left + zone.right) / 2f
            assertTrue("overlay: the outline centre $outlineCentre at $s expected within 0.5 of the zone centre $zoneCentre", Math.abs(outlineCentre - zoneCentre) <= 0.5f)
        }
    }

    /** A failure means the art box edges in the path data have drifted from the named constants: the silhouette's left and right, and the X and the check's left edge and width. */
    @Test
    fun `the art box edges in the data are the constants`() {
        val silhouette = TileArt.silhouette.flatMap { xsOf(it) }
        assertEquals("overlay: the silhouette left in the data expected ${ArtMetrics.SILHOUETTE_LEFT}", ArtMetrics.SILHOUETTE_LEFT, lowest(silhouette), pixel)
        assertEquals("overlay: the silhouette right in the data expected ${ArtMetrics.SILHOUETTE_RIGHT}", ArtMetrics.SILHOUETTE_RIGHT, highest(silhouette), pixel)
        val cancel = xsOf(ControlArt.cancel)
        assertEquals("overlay: the X left in the data expected ${ArtMetrics.CONTROL_BOX_LEFT}", ArtMetrics.CONTROL_BOX_LEFT, lowest(cancel), pixel)
        assertEquals("overlay: the X width in the data expected ${ArtMetrics.CONTROL_BOX_WIDTH}", ArtMetrics.CONTROL_BOX_WIDTH, highest(cancel) - lowest(cancel), pixel)
        val send = xsOf(ControlArt.send)
        assertEquals("overlay: the check left in the data expected ${ArtMetrics.CONTROL_BOX_LEFT}", ArtMetrics.CONTROL_BOX_LEFT, lowest(send), pixel)
        assertEquals("overlay: the check width in the data expected ${ArtMetrics.CONTROL_BOX_WIDTH}", ArtMetrics.CONTROL_BOX_WIDTH, highest(send) - lowest(send), pixel)
    }

    /** A failure means the draw transform of the X or the check does not put its path box on the placed left and right edges, or its centre off the silhouette centre line. */
    @Test
    fun `the draw transform puts the button path box on the placed edges`() {
        for (s in listOf(64, 168, 192)) {
            for ((name, p) in listOf("X" to ControlLayout.cancel(s), "check" to ControlLayout.send(s))) {
                assertEquals("overlay: the $name left from the draw transform at $s", p.left, p.translateX + ArtMetrics.CONTROL_BOX_LEFT * p.scale, pixel)
                assertEquals("overlay: the $name right from the draw transform at $s", p.right, p.translateX + (ArtMetrics.CONTROL_BOX_LEFT + ArtMetrics.CONTROL_BOX_WIDTH) * p.scale, pixel)
                assertEquals("overlay: the $name centre line from the draw transform at $s", ControlLayout.silhouetteCentreY(s), p.translateY + ArtMetrics.CONTROL_BOX_CENTRE_Y * p.scale, pixel)
            }
        }
    }
}
