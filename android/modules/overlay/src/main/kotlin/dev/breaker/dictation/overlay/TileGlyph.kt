package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.TruckingPalette

/**
 * The three colours of the plain microphone tile, as opaque ARGB ints.
 *
 * The tile does not draw with these: [TileStyle.look] gives the colours it draws with. They
 * describe the microphone picture alone: [background] is the surface behind it, [glyph] is the
 * colour of the body and [outline] is the colour of the trim.
 */
internal data class TileColors(val background: Int, val glyph: Int, val outline: Int)

/**
 * The microphone picture's colours for a palette: the surface colour behind a glyph in the
 * primary colour with trim-coloured detail, so light and dark mode each follow
 * their own palette and nothing here holds a colour of its own. The tile itself does not use
 * this; it draws with [TileStyle.look].
 */
internal fun tileColors(palette: TruckingPalette): TileColors = TileColors(
    background = palette.surface.argb,
    glyph = palette.primary.argb,
    outline = palette.trim.argb,
)

/**
 * The fixed sizes of the tile, in dp.
 *
 * The tile is at least as large as the design's smallest touch target, its
 * corner radius is no larger than the design's hard-edge limit, and the outline is
 * a thin line.
 */
internal object TileMetrics {
    /** Width and height of the tile. */
    const val TILE_SIZE_DP = 64

    /** Radius of the tile's corners. */
    const val CORNER_RADIUS_DP = 4

    /** Thickness of the outline lines. */
    const val OUTLINE_DP = 2

    /** Thickness of the glowing outline drawn along the edge of the microphone. */
    const val RING_DP = 2

    /** Gap in pixels cut from each side of a grille slot of the level meter, so neighbouring slots show a thin line between them. */
    const val SEGMENT_GAP_PX = 1
}
