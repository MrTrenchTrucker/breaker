package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.TruckingPalette

/**
 * Which part of the glyph a rectangle belongs to, and so which colour it is drawn in.
 *
 * [BODY] is drawn in the glyph colour. [GRILLE] and [OUTLINE] are drawn in the
 * outline colour.
 */
internal enum class GlyphRole { BODY, GRILLE, OUTLINE }

/**
 * One rectangle of the glyph in the unit square.
 *
 * Both axes run from 0 to 1 with the origin at the top-left, so a view of any
 * size scales the rectangle by its own width and height. A rectangle has a
 * positive width and height and lies inside the unit square.
 */
internal data class GlyphRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
    val role: GlyphRole,
)

/**
 * The picture on the tile: a simple citizens-band microphone made of rectangles.
 *
 * This is a stand-in. It is only a body, a grille, a handle and thin outlines,
 * enough for the tile to be recognisable and tappable, and the real art
 * replaces it later. Nothing outside this object depends on how many rectangles
 * there are or where they sit, only on their roles and on all of them lying
 * inside the unit square.
 *
 * The rectangles are listed in drawing order, so a later one is painted over an
 * earlier one: the body and the handle first, then the grille over the body,
 * then the thin outlines over both.
 *
 * The outline lines are [TileMetrics.OUTLINE_DP] thick: in the unit square that
 * is [TileMetrics.OUTLINE_DP] over [TileMetrics.TILE_SIZE_DP], the same
 * thickness on both axes of the square tile.
 */
internal object TileGlyph {
    /** Thickness of an outline line in the unit square. */
    private val outline: Float =
        TileMetrics.OUTLINE_DP.toFloat() / TileMetrics.TILE_SIZE_DP.toFloat()

    val rects: List<GlyphRect> = listOf(
        // The head of the microphone.
        GlyphRect(0.30f, 0.10f, 0.70f, 0.55f, GlyphRole.BODY),
        // The handle below the head.
        GlyphRect(0.40f, 0.55f, 0.60f, 0.88f, GlyphRole.BODY),
        // The grille: three thin bars across the head.
        GlyphRect(0.36f, 0.20f, 0.64f, 0.25f, GlyphRole.GRILLE),
        GlyphRect(0.36f, 0.31f, 0.64f, 0.36f, GlyphRole.GRILLE),
        GlyphRect(0.36f, 0.42f, 0.64f, 0.47f, GlyphRole.GRILLE),
        // Outline of the head: top, bottom, left, right.
        GlyphRect(0.30f, 0.10f, 0.70f, 0.10f + outline, GlyphRole.OUTLINE),
        GlyphRect(0.30f, 0.55f - outline, 0.70f, 0.55f, GlyphRole.OUTLINE),
        GlyphRect(0.30f, 0.10f, 0.30f + outline, 0.55f, GlyphRole.OUTLINE),
        GlyphRect(0.70f - outline, 0.10f, 0.70f, 0.55f, GlyphRole.OUTLINE),
        // Outline of the handle: left, right, bottom.
        GlyphRect(0.40f, 0.55f, 0.40f + outline, 0.88f, GlyphRole.OUTLINE),
        GlyphRect(0.60f - outline, 0.55f, 0.60f, 0.88f, GlyphRole.OUTLINE),
        GlyphRect(0.40f, 0.88f - outline, 0.60f, 0.88f, GlyphRole.OUTLINE),
    )
}

/**
 * The three colours of the plain microphone tile, as opaque ARGB ints.
 *
 * The tile does not draw with these: [TileStyle.look] gives the colours it draws with. They
 * describe the microphone picture alone: [background] is the surface behind
 * it, [glyph] is for the [GlyphRole.BODY] rectangles and [outline] is for the [GlyphRole.GRILLE]
 * and [GlyphRole.OUTLINE] ones.
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
    const val TILE_SIZE_DP = 56

    /** Radius of the tile's corners. */
    const val CORNER_RADIUS_DP = 4

    /** Thickness of the outline lines. */
    const val OUTLINE_DP = 2

    /** Thickness of the ring drawn around the microphone. */
    const val RING_DP = 2

    /** Gap in pixels cut from each side of a meter segment, so neighbouring segments show a thin line between them. */
    const val SEGMENT_GAP_PX = 1
}
