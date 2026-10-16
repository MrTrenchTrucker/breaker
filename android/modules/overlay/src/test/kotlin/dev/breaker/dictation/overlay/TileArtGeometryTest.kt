package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
/**
 * The plain geometry of the art: the busy sign, the glow widths and the art-to-cell frame. Every expected
 * number is a literal worked out from the named ArtMetrics constants. The busy ring is centred on the
 * approved point 512, 500, its radius is the ring mid radius 394, the red ring and slash are 92 art units
 * wide and the dark outline 124. The slash reach is the named 252.073, so the bar runs from 512 minus
 * 252.073, which is 259.927, and 500 minus 252.073, which is 247.927, to 764.073 and 752.073.
 */
class TileArtGeometryTest {

    private val busy = ArtGeometry.busySign()

    /** A failure means the busy ring is not centred at 512 500 with radius 394, red stroke 92 and dark outline 124 art units. */
    @Test
    fun `the busy ring is centred at 512 500 with radius 394 stroke 92 and outline 124`() {
        assertEquals("overlay: the busy centre x expected 512", 512f, busy.centreX, 0f)
        assertEquals("overlay: the busy centre y expected 500", 500f, busy.centreY, 0f)
        assertEquals("overlay: the busy radius expected 394", 394f, busy.radius, 0f)
        assertEquals("overlay: the busy stroke expected 92", 92f, busy.stroke, 0f)
        assertEquals("overlay: the busy outline stroke expected 124", 124f, busy.outlineStroke, 0f)
    }

    /** A failure means the slash ends are not at the named reach on the 45 degree line, or the two ends are not mirror images through the centre. */
    @Test
    fun `the slash runs along the 45 degree line through the centre`() {
        assertEquals("overlay: the slash from x expected 259.927", 259.927f, busy.slashFromX, 0.001f)
        assertEquals("overlay: the slash from y expected 247.927", 247.927f, busy.slashFromY, 0.001f)
        assertEquals("overlay: the slash to x expected 764.073", 764.073f, busy.slashToX, 0.001f)
        assertEquals("overlay: the slash to y expected 752.073", 752.073f, busy.slashToY, 0.001f)
        assertEquals("overlay: the from offsets expected equal in x and y", busy.slashFromX - busy.centreX, busy.slashFromY - busy.centreY, 0.001f)
        assertEquals("overlay: the to offsets expected equal in x and y", busy.slashToX - busy.centreX, busy.slashToY - busy.centreY, 0.001f)
        assertEquals("overlay: the to end expected the mirror of the from end", busy.centreX - busy.slashFromX, busy.slashToX - busy.centreX, 0.001f)
    }

    /** A failure means a slash end does not sit the named reach from the centre: the bar would clear or bury the ring wrongly. */
    @Test
    fun `each end of the slash sits the named reach from the centre`() {
        assertEquals("overlay: the from reach expected the named reach", busy.slashReach, busy.centreX - busy.slashFromX, 0.001f)
        assertEquals("overlay: the from reach in y expected the named reach", busy.slashReach, busy.centreY - busy.slashFromY, 0.001f)
        assertEquals("overlay: the to reach expected the named reach", busy.slashReach, busy.slashToX - busy.centreX, 0.001f)
        assertEquals("overlay: the to reach in y expected the named reach", busy.slashReach, busy.slashToY - busy.centreY, 0.001f)
    }

    /** A failure means the slash runs the other way: its start is not up and left of the centre, or its end is not down and right. */
    @Test
    fun `the slash starts up left of the centre and ends down right of it`() {
        assertTrue("overlay: the slash start expected up and left of the centre", busy.slashFromX < busy.centreX && busy.slashFromY < busy.centreY)
        assertTrue("overlay: the slash end expected down and right of the centre", busy.slashToX > busy.centreX && busy.slashToY > busy.centreY)
    }

    /** A failure means a glow layer is not 4, 6 or 8 dp wide: the ring width plus the layer step on each side. */
    @Test
    fun `the glow layers are 4 6 and 8 dp wide for layers 0 1 and 2`() {
        assertEquals("overlay: the layer 0 glow expected 4 dp", 4f, ArtGeometry.glowWidthDp(0), 0f)
        assertEquals("overlay: the layer 1 glow expected 6 dp", 6f, ArtGeometry.glowWidthDp(1), 0f)
        assertEquals("overlay: the layer 2 glow expected 8 dp", 8f, ArtGeometry.glowWidthDp(2), 0f)
    }

    /** A failure means the frame scale of a 56 pixel cell is not the cell width times 0.90 over 1024, which is 0.04921875. */
    @Test
    fun `the frame scale of a 56 pixel cell comes from the art fill and the box`() {
        assertEquals("overlay: the scale at 0 0 expected 0.04921875", 0.04921875f, ArtGeometry.frame(TileRect(0, 0, 56, 56)).scale, 0.000001f)
        assertEquals("overlay: the scale at 10 20 expected 0.04921875", 0.04921875f, ArtGeometry.frame(TileRect(10, 20, 66, 76)).scale, 0.000001f)
    }

    /** A failure means the art is not centred on the cell: the centre is the cell left or top plus half its width or height. */
    @Test
    fun `the frame centre is the middle of the cell`() {
        val at0 = ArtGeometry.frame(TileRect(0, 0, 56, 56))
        assertEquals("overlay: the centre x at 0 0 expected 28", 28f, at0.centreX, 0f)
        assertEquals("overlay: the centre y at 0 0 expected 28", 28f, at0.centreY, 0f)
        val at10 = ArtGeometry.frame(TileRect(10, 20, 66, 76))
        assertEquals("overlay: the centre x at 10 20 expected 38", 38f, at10.centreX, 0f)
        assertEquals("overlay: the centre y at 10 20 expected 48", 48f, at10.centreY, 0f)
    }

    /** A failure means the art box is not moved back by half its 1024 size on both axes. */
    @Test
    fun `the frame origin is minus half the art box on both axes`() {
        val frame = ArtGeometry.frame(TileRect(0, 0, 56, 56))
        assertEquals("overlay: the origin x expected -512", -512f, frame.originX, 0f)
        assertEquals("overlay: the origin y expected -512", -512f, frame.originY, 0f)
    }

    /** A failure means a cell that is not square takes its scale from the height, or its centre from the wrong edges. */
    @Test
    fun `a non square cell takes its scale from the width and its centre from both edges`() {
        val frame = ArtGeometry.frame(TileRect(5, 7, 61, 47))
        assertEquals("overlay: the scale expected 0.04921875 from the width 56", 0.04921875f, frame.scale, 0.000001f)
        assertEquals("overlay: the centre x expected 33", 33f, frame.centreX, 0f)
        assertEquals("overlay: the centre y expected 27", 27f, frame.centreY, 0f)
    }
}
