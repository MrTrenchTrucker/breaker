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

    /** The real TileArtGeometry.kt, read as text. */
    private fun geometryText(): String = ModuleFiles.mainTexts().getValue("TileArtGeometry.kt")

    /** [text] with [old] replaced by [new]; fails by name when [old] is not in [text], so a control cannot go stale unseen. */
    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** The problems with the halo's numbers in [source]: the layer count, the two alpha integers and the power law must be the named constants. */
    private fun haloNumberProblems(source: String): List<String> {
        val code = SourceText.code(source)
        return listOfNotNull(
            "the halo layer count is not the named HALO_LAYERS".takeUnless { Regex("""const val HALO_LAYERS: Int = 16""").containsMatchIn(code) },
            "the inner halo alpha is not the named HALO_ALPHA_INNER".takeUnless { Regex("""const val HALO_ALPHA_INNER: Int = 6""").containsMatchIn(code) },
            "the outer halo alpha is not the named HALO_ALPHA_OUTER".takeUnless { Regex("""const val HALO_ALPHA_OUTER: Int = 2""").containsMatchIn(code) },
            "the halo reach is not the power law".takeUnless { Regex("""\.pow\(HALO_LAYER_POWER\)""").containsMatchIn(code) },
        )
    }

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

    /** A failure means the frame scale of a 64 pixel cell is not the cell width times 0.90 over 1024, which is 0.05625. */
    @Test
    fun `the frame scale of a 64 pixel cell comes from the art fill and the box`() {
        assertEquals("overlay: the scale at 0 0 expected 0.05625", 0.05625f, ArtGeometry.frame(TileRect(0, 0, 64, 64)).scale, 0.000001f)
        assertEquals("overlay: the scale at 10 20 expected 0.05625", 0.05625f, ArtGeometry.frame(TileRect(10, 20, 74, 84)).scale, 0.000001f)
    }

    /** A failure means the art is not centred on the cell: the centre is the cell left or top plus half its width or height. */
    @Test
    fun `the frame centre is the middle of the cell`() {
        val at0 = ArtGeometry.frame(TileRect(0, 0, 64, 64))
        assertEquals("overlay: the centre x at 0 0 expected 32", 32f, at0.centreX, 0f)
        assertEquals("overlay: the centre y at 0 0 expected 32", 32f, at0.centreY, 0f)
        val at10 = ArtGeometry.frame(TileRect(10, 20, 74, 84))
        assertEquals("overlay: the centre x at 10 20 expected 42", 42f, at10.centreX, 0f)
        assertEquals("overlay: the centre y at 10 20 expected 52", 52f, at10.centreY, 0f)
    }

    /** A failure means the art box is not moved back by half its 1024 size on both axes. */
    @Test
    fun `the frame origin is minus half the art box on both axes`() {
        val frame = ArtGeometry.frame(TileRect(0, 0, 64, 64))
        assertEquals("overlay: the origin x expected -512", -512f, frame.originX, 0f)
        assertEquals("overlay: the origin y expected -512", -512f, frame.originY, 0f)
    }

    /** A failure means a cell that is not square takes its scale from the width, or its centre from the wrong edges. */
    @Test
    fun `a non square cell takes its scale from the width and its centre from both edges`() {
        val frame = ArtGeometry.frame(TileRect(5, 7, 69, 47))
        assertEquals("overlay: the scale expected 0.05625 from the width 64", 0.05625f, frame.scale, 0.000001f)
        assertEquals("overlay: the centre x expected 37", 37f, frame.centreX, 0f)
        assertEquals("overlay: the centre y expected 27", 27f, frame.centreY, 0f)
    }

    /**
     * A failure means the halo is not 16 soft layers drawn widest first, its reach is not the power law
     * ((layer + 1) / 16) to the 0.35, or its alphas are not 6 and 2 of full scale 255. Each pinned number
     * has a control: the real source is edited and the rule must fire, so the pin cannot pass vacuously.
     */
    @Test
    fun `the halo layers are 16 soft layers with the power-law reach and the two alpha integers`() {
        assertEquals("overlay: the halo layer count expected 16", 16, ArtGeometry.haloLayerCount())
        assertEquals("overlay: the inner halo alpha integer expected 6", 6, ArtGeometry.HALO_ALPHA_INNER)
        assertEquals("overlay: the outer halo alpha integer expected 2", 2, ArtGeometry.HALO_ALPHA_OUTER)
        assertEquals("overlay: the first halo reach fraction expected 0.378929", 0.378929f, ArtGeometry.haloLayerFraction(0), 0.000001f)
        assertEquals("overlay: the last halo reach fraction expected 1.0", 1.0f, ArtGeometry.haloLayerFraction(ArtGeometry.haloLayerCount() - 1), 0.000001f)
        assertEquals("overlay: the inner halo alpha expected 0.023529412", 0.023529412f, ArtGeometry.haloLayerAlpha(0), 0.000001f)
        assertEquals("overlay: the outer halo alpha expected 0.0078431373", 0.0078431373f, ArtGeometry.haloLayerAlpha(ArtGeometry.haloLayerCount() - 1), 0.000001f)

        val source = geometryText()
        assertQuiet("the real halo numbers", haloNumberProblems(source))
        assertFires("a halo layer count of 15", haloNumberProblems(edit(source, "const val HALO_LAYERS: Int = 16", "const val HALO_LAYERS: Int = 15")), "HALO_LAYERS")
        assertFires("an inner halo alpha of 7", haloNumberProblems(edit(source, "const val HALO_ALPHA_INNER: Int = 6", "const val HALO_ALPHA_INNER: Int = 7")), "HALO_ALPHA_INNER")
        assertFires("an outer halo alpha of 1", haloNumberProblems(edit(source, "const val HALO_ALPHA_OUTER: Int = 2", "const val HALO_ALPHA_OUTER: Int = 1")), "HALO_ALPHA_OUTER")
        assertFires("the power law dropped", haloNumberProblems(edit(source, ".pow(HALO_LAYER_POWER)", ".pow(1f)")), "power law")
    }

    /**
     * A failure means the halo's alpha does not fade: it is flat, or it is not monotone from the
     * innermost to the outermost layer. The assertion is proven able to fail by two planted alpha
     * lists, a flat one and a rising one, which the same predicate must refuse.
     */
    @Test
    fun `the halo alpha fades monotonically from the innermost to the outermost layer`() {
        val alphas = (0 until ArtGeometry.haloLayerCount()).map { ArtGeometry.haloLayerAlpha(it) }
        assertEquals("overlay: the halo's first alpha is not the inner integer", 6f / 255f, alphas.first(), 0.000001f)
        assertEquals("overlay: the halo's last alpha is not the outer integer", 2f / 255f, alphas.last(), 0.000001f)
        assertTrue("overlay: the halo's alphas are not monotone decreasing: $alphas", alphas.zipWithNext().all { (a, b) -> b < a })

        val flat = List(alphas.size) { alphas.first() }
        val rising = alphas.reversed()
        assertTrue("overlay: the control accepted a flat alpha list", !(flat.zipWithNext().all { (a, b) -> b < a }))
        assertTrue("overlay: the control accepted a rising alpha list", !(rising.zipWithNext().all { (a, b) -> b < a }))
    }

    /** Today's tile glow reach for a silhouette half-extent: the silhouette's half-extent plus the outer glow's half width, in half-cell widths at the tile's cell. */
    private fun tileReach(halfExtent: Float): Float =
        ArtGeometry.reachHalfCell(halfExtent, TileMetrics.TILE_SIZE_DP.toFloat())

    /** A failure means the halo's upward reach is not the capped one: it would pass the card bottom and leave the card. */
    @Test
    fun `the halo's upward reach is the capped reach`() {
        val cell = TileRect(0, 0, 200, 200)
        val density = 1f
        val cellSideDp = cell.width / density
        assertTrue(
            "overlay: the test cell must be one where the cap binds, found ${GlowSpec.SCREEN.upward * cellSideDp / 2f} against the card bottom ${ArtGeometry.cardBottomDp(cellSideDp)}",
            GlowSpec.SCREEN.upward * cellSideDp / 2f > ArtGeometry.cardBottomDp(cellSideDp),
        )
        val halo = ArtGeometry.halo(cell, density, GlowSpec.SCREEN)
        assertEquals(
            "overlay: the halo's upward reach expected the capped reach times the density",
            GlowSpec.upwardReachDp(GlowSpec.SCREEN, cellSideDp) * density,
            halo.upwardPx,
            0.0001f,
        )
        assertTrue(
            "overlay: the halo's upward reach must be held to the card bottom, found ${halo.upwardPx} against ${ArtGeometry.cardBottomDp(cellSideDp) * density}",
            halo.upwardPx <= ArtGeometry.cardBottomDp(cellSideDp) * density + 0.0001f,
        )
    }

    /** A failure means the tile's glow is not today's values: its three reaches must be the silhouette's half-extents plus the outer glow's half width. */
    @Test
    fun `the tile's glow is today's values`() {
        assertEquals("overlay: the tile's sideways reach expected the silhouette's half width plus the glow", tileReach(ArtMetrics.SILHOUETTE_HALF_WIDTH), GlowSpec.TILE.sideways, 0.000001f)
        assertEquals("overlay: the tile's downward reach expected the silhouette's half height plus the glow", tileReach(ArtMetrics.SILHOUETTE_HALF_HEIGHT), GlowSpec.TILE.downward, 0.000001f)
        assertEquals("overlay: the tile's upward reach expected the silhouette's half height plus the glow", tileReach(ArtMetrics.SILHOUETTE_HALF_HEIGHT), GlowSpec.TILE.upward, 0.000001f)
    }

    /** A failure means the Home mic's halo is not the approved three reaches, or a spec's kind is not its own. */
    @Test
    fun `the screen halo is the approved reaches and each spec carries its own kind`() {
        assertEquals("overlay: the screen's sideways reach expected 1.40", 1.40f, GlowSpec.SCREEN.sideways, 0f)
        assertEquals("overlay: the screen's downward reach expected 1.40", 1.40f, GlowSpec.SCREEN.downward, 0f)
        assertEquals("overlay: the screen's upward reach expected 1.40", 1.40f, GlowSpec.SCREEN.upward, 0f)
        assertEquals("overlay: the screen's kind expected SCREEN", GlowKind.SCREEN, GlowSpec.SCREEN.kind)
        assertEquals("overlay: the tile's kind expected TILE", GlowKind.TILE, GlowSpec.TILE.kind)
        assertEquals("overlay: the glow kinds expected TILE and SCREEN", listOf(GlowKind.TILE, GlowKind.SCREEN), GlowKind.entries.toList())
    }
}
