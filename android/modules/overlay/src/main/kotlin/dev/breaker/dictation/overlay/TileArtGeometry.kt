package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.TruckingTokens
import kotlin.math.pow

/**
 * The busy sign in art units: a circle at ([centreX], [centreY]) and a flat slash along the diagonal through it.
 * [stroke] is the width of the red ring and the red slash; [outlineStroke] is the width of the dark outline that
 * wraps them (the stroke plus the outline on each side). The slash centre line runs from ([slashFromX],
 * [slashFromY]) to ([slashToX], [slashToY]), each end [slashReach] from the centre along the 45 degree diagonal.
 */
internal class BusySign(
    val centreX: Float,
    val centreY: Float,
    val radius: Float,
    val stroke: Float,
    val outlineStroke: Float,
    val slashReach: Float,
    val slashFromX: Float,
    val slashFromY: Float,
    val slashToX: Float,
    val slashToY: Float,
)

/**
 * Where the art box goes in one cell, in pixels: [scale] is the pixels per art unit, ([centreX], [centreY])
 * is the middle of the cell, and [originX] and [originY] move the art box, in art units, so that its middle
 * lands on the middle of the cell.
 */
internal class ArtFrame(
    val scale: Float,
    val centreX: Float,
    val centreY: Float,
    val originX: Float,
    val originY: Float,
)

/**
 * The numbers the microphone art and its glow are drawn with. This is plain Kotlin with no platform
 * names: [TileArtDraw] only passes these numbers to the canvas.
 */
internal object ArtGeometry {

    /**
     * The busy sign: the ring is centred on the approved point in the art box (off the box centre), its radius is
     * the ring's mid radius and its stroke the ring width, and the dark outline is the stroke plus the outline on
     * each side. The slash is a bar along the 45 degree diagonal through the centre, each end [ArtMetrics.BUSY_SLASH_REACH]
     * from the centre, so it clears the ring by the 6 the generator leaves between the bar and the ring.
     */
    fun busySign(): BusySign {
        val reach = ArtMetrics.BUSY_SLASH_REACH
        return BusySign(
            centreX = ArtMetrics.BUSY_CENTRE_X,
            centreY = ArtMetrics.BUSY_CENTRE_Y,
            radius = ArtMetrics.BUSY_RADIUS,
            stroke = ArtMetrics.BUSY_STROKE,
            outlineStroke = ArtMetrics.BUSY_OUTLINE_STROKE,
            slashReach = reach,
            slashFromX = ArtMetrics.BUSY_CENTRE_X - reach,
            slashFromY = ArtMetrics.BUSY_CENTRE_Y - reach,
            slashToX = ArtMetrics.BUSY_CENTRE_X + reach,
            slashToY = ArtMetrics.BUSY_CENTRE_Y + reach,
        )
    }

    /** The width in dp of glow layer [layer] (0 innermost): the ring width plus the layer's step on each side. */
    fun glowWidthDp(layer: Int): Float = TileMetrics.RING_DP + (layer + 1) * ArtMetrics.GLOW_STEP_DP * 2

    /** The art box in [cell]: scaled from the cell width, centred on the cell, with its origin at minus half the box. */
    fun frame(cell: TileRect): ArtFrame {
        val scale = cell.width * ArtMetrics.ART_FILL / TileArt.BOX
        return ArtFrame(
            scale = scale,
            centreX = cell.left + cell.width / 2f,
            centreY = cell.top + cell.height / 2f,
            originX = -TileArt.BOX / 2f,
            originY = -TileArt.BOX / 2f,
        )
    }

    /** The art scale, in dp per art unit, of a cell whose side is [cellSideDp] dp. */
    fun scaleDp(cellSideDp: Float): Float = frame(TileRect(0, 0, cellSideDp.toInt(), cellSideDp.toInt())).scale

    /** One half-cell width in art units, for a cell of side [cellSideDp] dp: half the cell side over the art scale. */
    fun halfCellUnits(cellSideDp: Float): Float = cellSideDp / 2f / scaleDp(cellSideDp)

    /**
     * The distance from the mic centre up to the card's bottom edge, in dp, for a cell of side [cellSideDp] dp:
     * half the cell, less the silhouette centre's own offset above the cell centre (the silhouette sits closer
     * to the card), plus the extra-large space the card leaves below the cell. The offset is negative here, so
     * the sum subtracts it.
     */
    fun cardBottomDp(cellSideDp: Float): Float =
        cellSideDp / 2f + (ArtMetrics.SILHOUETTE_CENTRE_Y - TileArt.BOX / 2f) * scaleDp(cellSideDp) +
            TruckingTokens.metrics.spaceXlDp.toFloat()

    /**
     * The reach, in half-cell widths, of a silhouette half-extent of [halfExtent] art units at a cell of side
     * [cellSideDp] dp, with the glow's outer half width added, so a halo drawn from it would meet the same edge
     * the glow meets today.
     */
    fun reachHalfCell(halfExtent: Float, cellSideDp: Float): Float =
        (halfExtent + glowWidthDp(ArtMetrics.GLOW_LAYERS - 1) / 2f / scaleDp(cellSideDp)) / halfCellUnits(cellSideDp)

    /**
     * The half width in dp of the glow's outer edge, from a [reach] in half-cell widths at a cell of side
     * [cellSideDp] dp and the silhouette's half-extent [halfExtent] on that axis: the reach to the outer edge,
     * less the silhouette's own half-extent, times the art scale.
     */
    fun glowOuterHalfWidthDp(reach: Float, halfExtent: Float, cellSideDp: Float): Float =
        (reach * halfCellUnits(cellSideDp) - halfExtent) * scaleDp(cellSideDp)

    /**
     * The half width in dp of the tile's outer glow layer, from the tile's [spec] at the tile's own
     * [TileMetrics.TILE_SIZE_DP] dp cell: the least of the three reaches' implied half widths, since one stroke
     * width is drawn on every side and the glow must stay inside all three. The tile's three reaches name the same
     * outer edge there, so the least of them is the one that binds. The cell side is the spec's own, not the cell
     * actually drawn: the tile's glow is a fixed dp width, and reading the reaches at the drawn cell would rescale
     * it, which would move the tile's pixels.
     */
    fun glowOuterHalfWidthDp(spec: HaloReach): Float = minOf(
        glowOuterHalfWidthDp(spec.sideways, ArtMetrics.SILHOUETTE_HALF_WIDTH, TileMetrics.TILE_SIZE_DP.toFloat()),
        glowOuterHalfWidthDp(spec.downward, ArtMetrics.SILHOUETTE_HALF_HEIGHT, TileMetrics.TILE_SIZE_DP.toFloat()),
        glowOuterHalfWidthDp(spec.upward, ArtMetrics.SILHOUETTE_HALF_HEIGHT, TileMetrics.TILE_SIZE_DP.toFloat()),
    )

    /** The half width in dp of the glow's outer layer as it is drawn today, for a surface whose spec names a halo and not a glow. */
    fun glowOuterHalfWidthDp(): Float = glowWidthDp(ArtMetrics.GLOW_LAYERS - 1) / 2f

    /**
     * The width in dp of glow layer [layer] when the outer layer's half width is [outerHalfWidthDp]: the outer
     * layer's width, less the glow step on each side for every layer in from the edge.
     */
    fun glowWidthDp(layer: Int, outerHalfWidthDp: Float): Float =
        outerHalfWidthDp * 2f - (ArtMetrics.GLOW_LAYERS - 1 - layer) * ArtMetrics.GLOW_STEP_DP * 2f

    /**
     * The layers of the halo, from the innermost out, drawn widest first: sixteen soft layers whose reach
     * follows the power law below, each a little wider than the one before. This is the halo's own layer
     * count, not [ArtMetrics.GLOW_LAYERS], which is the glow's three strokes.
     */
    const val HALO_LAYERS: Int = 16

    /** The exponent of the halo's reach law: layer [layer]'s reach is ((layer + 1) / [HALO_LAYERS]) raised to this power. */
    const val HALO_LAYER_POWER: Float = 0.35f

    /** The alpha of the halo layers 0 to [HALO_LAYERS] - 2, of full scale 255: the soft inner body of the halo. */
    const val HALO_ALPHA_INNER: Int = 4

    /** The alpha of the outermost halo layer (index [HALO_LAYERS] - 1), of full scale 255: the faintest edge of the halo. */
    const val HALO_ALPHA_OUTER: Int = 3

    /** The number of halo layers, [HALO_LAYERS]. */
    fun haloLayerCount(): Int = HALO_LAYERS

    /** The alpha of halo layer [layer] (0 innermost), as a share of full scale: 4 for layers 0 to [HALO_LAYERS] - 2, 3 for the last. */
    fun haloLayerAlpha(layer: Int): Float =
        if (layer >= HALO_LAYERS - 1) HALO_ALPHA_OUTER / 255f else HALO_ALPHA_INNER / 255f

    /** The share of its full reach that halo layer [layer] draws, 0 the innermost and the last the widest: ((layer + 1) / [HALO_LAYERS]) to the power [HALO_LAYER_POWER]. */
    fun haloLayerFraction(layer: Int): Float = ((layer + 1f) / HALO_LAYERS).pow(HALO_LAYER_POWER)

    /**
     * The halo behind the silhouette in the cell's own pixels: the mic centre ([centreX], [centreY]) and the three
     * reaches from it, the upward one already held to the card bottom by [GlowSpec].
     */
    internal class HaloGeometry(
        val centreX: Float,
        val centreY: Float,
        val sidewaysPx: Float,
        val downwardPx: Float,
        val upwardPx: Float,
    )

    /** The halo of the microphone in [cell], in the cell's own pixels, for the reaches of [reach]. */
    fun halo(cell: TileRect, density: Float, reach: HaloReach): HaloGeometry {
        val art = frame(cell)
        val cellSideDp = cell.width / density
        val halfCellDp = cellSideDp / 2f
        return HaloGeometry(
            centreX = art.centreX + (ArtMetrics.SILHOUETTE_CENTRE_X - TileArt.BOX / 2f) * art.scale,
            centreY = art.centreY + (ArtMetrics.SILHOUETTE_CENTRE_Y - TileArt.BOX / 2f) * art.scale,
            sidewaysPx = reach.sideways * halfCellDp * density,
            downwardPx = reach.downward * halfCellDp * density,
            upwardPx = GlowSpec.upwardReachDp(reach, cellSideDp) * density,
        )
    }
}
