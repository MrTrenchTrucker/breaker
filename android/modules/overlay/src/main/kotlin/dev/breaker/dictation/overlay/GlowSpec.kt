package dev.breaker.dictation.overlay

/**
 * Which surface a halo reach is drawn for: [TILE] the floating tile, [SCREEN] the Home mic. [TileArtDraw.drawGlow]
 * reads this kind to decide what to draw: a [TILE] reach draws the tile's three glow strokes only, a [SCREEN]
 * reach draws the Home mic's soft halo first and then the same three strokes.
 */
internal enum class GlowKind { TILE, SCREEN }

/**
 * One set of halo reaches, in half-cell widths measured from the mic centre: [sideways] to the left and to the
 * right, [downward] and [upward]. One half-cell width is half the mic cell's side, so a reach of 1.40 is 0.70 of
 * the cell side from the centre. [kind] names the surface the reach is drawn for, so it names what is drawn with
 * it: the tile's three strokes, or the Home mic's halo and then those strokes.
 */
internal class HaloReach(
    val sideways: Float,
    val downward: Float,
    val upward: Float,
    val kind: GlowKind = GlowKind.SCREEN,
)

/**
 * The named halo reaches of the surfaces that draw the microphone, each with the kind that says what is drawn
 * with it. [TILE] holds the reach the tile's glow has today, at the tile's own cell, and is drawn as the tile's
 * three glow strokes and nothing else. [SCREEN] names the Home mic's halo, whose upward reach is held to the
 * card bottom by [upwardReachDp]; the Home mic draws that halo first and then the same three strokes, so the
 * halo is what [SCREEN] selects.
 */
internal object GlowSpec {

    /** The tile: the reach the glow has today, in half-cell widths at the tile's 64 dp cell (the silhouette's own half-extent plus the outer glow's half width). */
    val TILE: HaloReach = HaloReach(
        sideways = ArtGeometry.reachHalfCell(ArtMetrics.SILHOUETTE_HALF_WIDTH, TileMetrics.TILE_SIZE_DP.toFloat()),
        downward = ArtGeometry.reachHalfCell(ArtMetrics.SILHOUETTE_HALF_HEIGHT, TileMetrics.TILE_SIZE_DP.toFloat()),
        upward = ArtGeometry.reachHalfCell(ArtMetrics.SILHOUETTE_HALF_HEIGHT, TileMetrics.TILE_SIZE_DP.toFloat()),
        kind = GlowKind.TILE,
    )

    /** The Home mic: the approved option, 1.40 half-cell widths on every side before the upward cap. */
    val SCREEN: HaloReach = HaloReach(sideways = 1.40f, downward = 1.40f, upward = 1.40f, kind = GlowKind.SCREEN)

    /** [reach]'s upward reach in dp, held to the card bottom above the mic centre. */
    fun upwardReachDp(reach: HaloReach, cellSideDp: Float): Float =
        minOf(reach.upward * cellSideDp / 2f, ArtGeometry.cardBottomDp(cellSideDp))
}
