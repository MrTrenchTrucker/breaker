package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

/**
 * The plain geometry of the art: the busy sign, the glow widths and the art-to-cell frame. Every expected
 * number is a literal worked out by awk; the commands are in the notes. The busy radius is 512 minus 60
 * minus half of 70, which is 417. The slash reach is 417 over the square root of 2, which is 294.863528 to
 * six decimals, so the slash runs from 217.136472 to 806.863528 on both axes.
 */
class TileArtGeometryTest {

    private val busy = ArtGeometry.busySign()

    /** A failure means the busy circle is not centred at 512 with radius 417 and stroke 70 art units. */
    @Test
    fun `the busy circle is centred at 512 with radius 417 and stroke 70`() {
        assertEquals("overlay: the busy centre expected 512", 512f, busy.centre, 0f)
        assertEquals("overlay: the busy radius expected 417", 417f, busy.radius, 0f)
        assertEquals("overlay: the busy stroke expected 70", 70f, busy.stroke, 0f)
    }

    /** A failure means the slash ends are not at the literal reach, or the two ends are not mirror images through the centre. */
    @Test
    fun `the slash runs along the 45 degree line through the centre`() {
        assertEquals("overlay: the slash from x expected 217.136472", 217.136472f, busy.slashFromX, 0.001f)
        assertEquals("overlay: the slash from y expected 217.136472", 217.136472f, busy.slashFromY, 0.001f)
        assertEquals("overlay: the slash to x expected 806.863528", 806.863528f, busy.slashToX, 0.001f)
        assertEquals("overlay: the slash to y expected 806.863528", 806.863528f, busy.slashToY, 0.001f)
        assertEquals("overlay: the from offsets expected equal in x and y", busy.slashFromX - busy.centre, busy.slashFromY - busy.centre, 0.001f)
        assertEquals("overlay: the to offsets expected equal in x and y", busy.slashToX - busy.centre, busy.slashToY - busy.centre, 0.001f)
        assertEquals("overlay: the to end expected the mirror of the from end", busy.centre - busy.slashFromX, busy.slashToX - busy.centre, 0.001f)
    }

    /** A failure means an end of the slash is not the radius from the centre: without the square root of 2 each end sits 589.7 away. */
    @Test
    fun `each end of the slash lies on the busy circle`() {
        val dxFrom = busy.slashFromX - busy.centre
        val dyFrom = busy.slashFromY - busy.centre
        val dxTo = busy.slashToX - busy.centre
        val dyTo = busy.slashToY - busy.centre
        assertEquals("overlay: the from end expected the radius from the centre", busy.radius, sqrt(dxFrom * dxFrom + dyFrom * dyFrom), 0.001f)
        assertEquals("overlay: the to end expected the radius from the centre", busy.radius, sqrt(dxTo * dxTo + dyTo * dyTo), 0.001f)
    }

    /** A failure means the slash runs the other way: its start is not up and left of the centre, or its end is not down and right. */
    @Test
    fun `the slash starts up left of the centre and ends down right of it`() {
        assertTrue("overlay: the slash start expected up and left of the centre", busy.slashFromX < busy.centre && busy.slashFromY < busy.centre)
        assertTrue("overlay: the slash end expected down and right of the centre", busy.slashToX > busy.centre && busy.slashToY > busy.centre)
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
