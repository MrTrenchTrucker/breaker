package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The tile's colours and its fixed sizes: the picture colours for each palette, the touch target, the corner
 * radius and the outline width. The picture itself is art data and is checked in the art tests.
 *
 * Colours are compared with the token values written out as hex literals (surface, primary and trim
 * of each palette), so a change in either side shows up here.
 */
class TileGlyphTest {
    private fun hex(argb: Int): String = "0x" + Integer.toHexString(argb).uppercase()

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

    /** A failure means the tile is smaller than the smallest touch target, or is not the documented 64 dp. */
    @Test
    fun `the tile is at least the minimum touch target`() {
        val minimum = TruckingTokens.metrics.minTouchTargetDp
        assertTrue(
            "android_overlay: the tile size ${TileMetrics.TILE_SIZE_DP} dp expected at least the minimum touch target $minimum dp",
            TileMetrics.TILE_SIZE_DP >= minimum,
        )
        assertEquals("android_overlay: the tile size expected 64 dp", 64, TileMetrics.TILE_SIZE_DP)
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
}
