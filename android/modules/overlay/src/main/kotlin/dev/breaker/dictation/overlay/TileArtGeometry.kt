package dev.breaker.dictation.overlay

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
}
