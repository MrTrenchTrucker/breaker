package dev.breaker.dictation.overlay

import kotlin.math.sqrt

/**
 * The busy sign in art units: a circle centred in the art box, and a flat slash from one side of the circle
 * to the other, along the diagonal. [stroke] is the width of both the circle and the slash. Each end of the
 * slash lies on the circle, so each end is [radius] from the centre.
 */
internal class BusySign(
    val centre: Float,
    val radius: Float,
    val stroke: Float,
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

    /** The busy sign: the circle is centred in the art box and kept clear of its edge by the margin and half the stroke. */
    fun busySign(): BusySign {
        val centre = TileArt.BOX / 2f
        val radius = centre - ArtMetrics.BUSY_MARGIN_UNITS - ArtMetrics.BUSY_STROKE_UNITS / 2f
        val reach = radius / sqrt(2f)
        return BusySign(
            centre = centre,
            radius = radius,
            stroke = ArtMetrics.BUSY_STROKE_UNITS,
            slashFromX = centre - reach,
            slashFromY = centre - reach,
            slashToX = centre + reach,
            slashToY = centre + reach,
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
