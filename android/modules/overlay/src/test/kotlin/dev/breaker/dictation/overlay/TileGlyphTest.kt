package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stand-in glyph, its colours and its sizes.
 *
 * Colours are compared with the token values written out as hex literals (surface, primary and trim
 * of each palette), so a change in either side shows up here.
 */
class TileGlyphTest {
    private fun hex(argb: Int): String = "0x" + Integer.toHexString(argb).uppercase()

    /** A failure means a glyph rectangle sticks out of the unit square, is flipped or empty, or the glyph has no rectangles at all. */
    @Test
    fun `every glyph rectangle lies inside the unit square and has an area`() {
        val rects = TileGlyph.rects
        assertTrue("android_overlay: the glyph expected at least one rectangle", rects.isNotEmpty())
        for ((index, rect) in rects.withIndex()) {
            assertTrue("android_overlay: rectangle $index expected left >= 0: $rect", rect.left >= 0f)
            assertTrue("android_overlay: rectangle $index expected top >= 0: $rect", rect.top >= 0f)
            assertTrue("android_overlay: rectangle $index expected right <= 1: $rect", rect.right <= 1f)
            assertTrue("android_overlay: rectangle $index expected bottom <= 1: $rect", rect.bottom <= 1f)
            assertTrue("android_overlay: rectangle $index expected left < right (a width): $rect", rect.left < rect.right)
            assertTrue("android_overlay: rectangle $index expected top < bottom (a height): $rect", rect.top < rect.bottom)
        }
    }

    /** A failure means a tile colour is not the palette's surface (background), primary (glyph) or trim (outline), in light or dark. */
    @Test
    fun `light and dark tile colours come from the palette`() {
        // Light: surface #F4F6F4, primary #1E7A46, trim #000000, each with the opaque alpha byte FF.
        val light = tileColors(TruckingTokens.LIGHT)
        assertEquals("android_overlay: light background expected the surface 0xFFF4F6F4", "0xFFF4F6F4", hex(light.background))
        assertEquals("android_overlay: light glyph expected the primary 0xFF1E7A46", "0xFF1E7A46", hex(light.glyph))
        assertEquals("android_overlay: light outline expected the trim 0xFF000000", "0xFF000000", hex(light.outline))

        // Dark: surface #161B1E, primary #2E9E5B, trim #FFFFFF.
        val dark = tileColors(TruckingTokens.DARK)
        assertEquals("android_overlay: dark background expected the surface 0xFF161B1E", "0xFF161B1E", hex(dark.background))
        assertEquals("android_overlay: dark glyph expected the primary 0xFF2E9E5B", "0xFF2E9E5B", hex(dark.glyph))
        assertEquals("android_overlay: dark outline expected the trim 0xFFFFFFFF", "0xFFFFFFFF", hex(dark.outline))
    }

    /** A failure means the tile is smaller than the smallest touch target, or is not the documented 56 dp. */
    @Test
    fun `the tile is at least the minimum touch target`() {
        val minimum = TruckingTokens.metrics.minTouchTargetDp
        assertTrue(
            "android_overlay: the tile size ${TileMetrics.TILE_SIZE_DP} dp expected at least the minimum touch target $minimum dp",
            TileMetrics.TILE_SIZE_DP >= minimum,
        )
        assertEquals("android_overlay: the tile size expected 56 dp", 56, TileMetrics.TILE_SIZE_DP)
    }

    /** A failure means the tile corners are rounder than the hard-edge limit, or the corner and outline sizes are not the documented 4 dp and 2 dp. */
    @Test
    fun `the tile corner radius is within the hard-edge limit`() {
        val limit = TruckingTokens.metrics.cornerRadiusDp
        assertTrue(
            "android_overlay: the corner radius ${TileMetrics.CORNER_RADIUS_DP} dp expected at most the hard-edge limit $limit dp",
            TileMetrics.CORNER_RADIUS_DP <= limit,
        )
        assertTrue("android_overlay: the corner radius expected not negative", TileMetrics.CORNER_RADIUS_DP >= 0)
        assertEquals("android_overlay: the corner radius expected 4 dp", 4, TileMetrics.CORNER_RADIUS_DP)
        assertEquals("android_overlay: the outline width expected 2 dp", 2, TileMetrics.OUTLINE_DP)
    }

    /** A failure means a glyph role (body, grille, outline or slash) has no rectangle, so part of the picture is never drawn, or a role was added or removed. */
    @Test
    fun `every glyph role is drawn at least once`() {
        assertEquals(
            "android_overlay: the glyph roles expected BODY, GRILLE, OUTLINE, SLASH in that order",
            listOf(GlyphRole.BODY, GlyphRole.GRILLE, GlyphRole.OUTLINE, GlyphRole.SLASH),
            GlyphRole.values().toList(),
        )
        for (role in GlyphRole.values()) {
            val count = TileGlyph.rects.count { it.role == role }
            assertTrue("android_overlay: the role $role expected at least one rectangle, found $count", count >= 1)
        }
    }

    /** A failure means the outline lines are not the outline width over the tile size in the unit square, so they draw too thick or too thin. */
    @Test
    fun `the outline thickness is the outline width over the tile size`() {
        // 2 dp over 56 dp is 2 / 56 = 0.0357142857 of the unit square; the literal is used here, not the production sum.
        val expected = 0.0357143f
        assertEquals("android_overlay: the literal expected equal to 2 / 56", 2f / 56f, expected, 0.00001f)

        val outlines = TileGlyph.rects.filter { it.role == GlyphRole.OUTLINE }
        assertEquals("android_overlay: expected 7 outline rectangles (4 for the head, 3 for the handle)", 7, outlines.size)
        for ((index, rect) in outlines.withIndex()) {
            val width = rect.right - rect.left
            val height = rect.bottom - rect.top
            val thickness = minOf(width, height)
            assertEquals(
                "android_overlay: outline $index expected a thickness of 0.0357143 (2 / 56) but the rectangle is $rect",
                expected,
                thickness,
                0.00001f,
            )
        }
    }

    /** A failure means the busy slash is not eight connected steps from corner to corner of the tile, or it is drawn under the microphone. */
    @Test
    fun `the slash runs corner to corner in eight steps on top of the microphone`() {
        val slash = TileGlyph.rects.filter { it.role == GlyphRole.SLASH }
        assertEquals("android_overlay: the slash expected 8 steps", 8, slash.size)
        assertEquals("android_overlay: the first step expected at the top left corner (left)", 0f, slash.first().left, 0.0001f)
        assertEquals("android_overlay: the first step expected at the top left corner (top)", 0f, slash.first().top, 0.0001f)
        assertEquals("android_overlay: the last step expected at the bottom right corner (right)", 1f, slash.last().right, 0.0001f)
        assertEquals("android_overlay: the last step expected at the bottom right corner (bottom)", 1f, slash.last().bottom, 0.0001f)
        for (i in 1 until slash.size) {
            assertTrue("android_overlay: step $i expected to move right of step ${i - 1}", slash[i].left > slash[i - 1].left)
            assertTrue("android_overlay: step $i expected to move down from step ${i - 1}", slash[i].top > slash[i - 1].top)
            assertTrue("android_overlay: step $i expected to touch step ${i - 1} across", slash[i].left <= slash[i - 1].right)
            assertTrue("android_overlay: step $i expected to touch step ${i - 1} down", slash[i].top <= slash[i - 1].bottom)
        }
        val lastPicture = TileGlyph.rects.indexOfLast { it.role != GlyphRole.SLASH }
        val firstSlash = TileGlyph.rects.indexOfFirst { it.role == GlyphRole.SLASH }
        assertTrue("android_overlay: the slash expected after the picture so it lies on top", firstSlash > lastPicture)
    }
}
