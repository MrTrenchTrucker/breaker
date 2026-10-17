package dev.breaker.dictation.overlay

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The shade factors of the faces, one per face. A factor above 0 moves the base colour toward white by
 * that share of the distance to white, a factor below 0 moves it toward black by its size, and 0 keeps it.
 */
internal object ArtFactors {
    const val TRIGGER_FACE: Float = 0.12f      // of the body colour
    const val TRIGGER_STRIPE: Float = 0.70f    // of the body colour
    const val LOWER_LEFT: Float = 0.08f        // of the body colour
    const val LOWER_RIGHT: Float = -0.55f      // of the body colour
    const val FRAME_TOP: Float = 0f            // of look.glyphOutline (the field itself)
    const val FRAME_LEFT: Float = -0.08f       // of look.glyphOutline
    const val FRAME_BOTTOM: Float = -0.37f     // of look.glyphOutline
    const val FRAME_RIGHT: Float = -0.26f      // of look.glyphOutline
    const val LEDGE_EDGE: Float = 0.10f        // of the body colour
    const val LEDGE_FACE: Float = -0.30f       // of look.glyphOutline
    const val LEDGE_END: Float = -0.50f        // of look.glyphOutline (both ends)
    const val SLOT_TOP: Float = -0.24f         // of the slot colour (lit or unlit)
    const val BUSY_OUTLINE_SHADE: Float = -0.85f // of look.danger: the dark outline under the red ring and slash (near black, not a fixed hex)
}

/** Sizes of the art and of its glow. */
internal object ArtMetrics {
    const val ART_FILL: Float = 0.90f          // the 1024 box is drawn this fraction of the cell side, centred in the cell
    const val GLOW_LAYERS: Int = 3
    const val GLOW_STEP_DP: Float = 1f         // each glow layer reaches this many dp further out on each side than the one before (its stroke is twice this wider)
    const val BUSY_CENTRE_X: Float = 512f      // the busy sign's centre x, in art units (the approved sign sits on the mic, off the box centre)
    const val BUSY_CENTRE_Y: Float = 500f      // the busy sign's centre y, in art units
    const val BUSY_RADIUS: Float = 394f        // the busy circle's radius: the ring's mid radius (outer 440, inner 348), in art units
    const val BUSY_STROKE: Float = 92f         // the ring and the slash width, in art units
    const val BUSY_OUTLINE_STROKE: Float = 124f // the black outline width: the stroke plus the 16 outline on each side, in art units
    const val BUSY_SLASH_REACH: Float = 252.073f // the slash centre-line's half length from the centre, in art units (the inner radius over root two, plus the 6 the bar clears the ring)
    const val CONTROL_OUTLINE_UNITS: Float = 22f // the trim outline around the X and the check, in art units, centred on the shape's edge
    const val CONTROL_MITER_LIMIT: Float = 10f // the miter limit of the svg outline (stroke-miterlimit 10)
    const val HALO_DP: Float = 2f              // the halo under the notice text only (the X and the check have no halo): it reaches this many dp beyond the text stroke on each side (its stroke is twice this wider than the stroke it sits under)
}

/**
 * The colour [color] moved toward white by [factor] when it is positive, or toward black by -[factor] when
 * it is negative, one channel at a time, with the alpha byte kept. The factor is taken in thousandths (the
 * factors here have at most two decimals), and each channel moves by its share of the distance to the
 * target in whole numbers: in thousandths, a half step goes up. A factor of 0 returns [color]; a factor
 * outside -1..1 is held to -1..1; a factor that is NaN returns [color].
 */
internal fun shade(color: Int, factor: Float): Int {
    if (factor.isNaN()) return color
    val clamped = factor.coerceIn(-1f, 1f)
    val permille = (abs(clamped) * 1000f).roundToInt()
    fun channel(c: Int): Int =
        if (clamped >= 0f) c + ((255 - c) * permille + 500) / 1000
        else c - (c * permille + 500) / 1000
    val red = channel((color ushr 16) and 0xFF)
    val green = channel((color ushr 8) and 0xFF)
    val blue = channel(color and 0xFF)
    return ((color ushr 24) shl 24) or (red shl 16) or (green shl 8) or blue
}

/**
 * The WCAG relative luminance of [color], from 0 (black) to 1 (white), with the alpha byte ignored. Each
 * channel is taken as its share of full scale and made linear light: divided by 12.92 when the share is at
 * most 0.03928, otherwise raised to the power 2.4 after the 0.055 offset and the division by 1.055. The
 * three channels are weighted 0.2126, 0.7152 and 0.0722. This is the one place the formula lives.
 */
internal fun relativeLuminance(color: Int): Double {
    fun linear(c: Int): Double {
        val v = c / 255.0
        return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
    }
    val r = linear((color ushr 16) and 0xFF)
    val g = linear((color ushr 8) and 0xFF)
    val b = linear(color and 0xFF)
    return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/**
 * The colour of the mic body: the darker of the text colour ([TileLook.control]) and the surface colour
 * ([TileLook.background]), judged by [relativeLuminance]. When the two are equally light, the background
 * wins. The body is dark in both themes, so the mic reads on either page.
 */
internal fun bodyColor(look: TileLook): Int =
    if (relativeLuminance(look.control) < relativeLuminance(look.background)) look.control else look.background

/**
 * True when slot [index] (0 is the leftmost) is drawn lit. Every slot is lit, whatever [lit] is, except in
 * RECORDING, where slot i is lit exactly when i is below [lit]. A [lit] above six lights all six; below
 * zero lights none.
 */
internal fun slotLit(state: TileState, index: Int, lit: Int): Boolean =
    state != TileState.RECORDING || index < lit

/**
 * The colour of one face of the mic in [look]. The body shapes and the well use [bodyColor]; the shading
 * faces (the trigger face and stripe, the lower shading and the ledge edge) shade the body colour by their
 * factors from ArtFactors. The frame, the ledge face and the ledge ends shade the outline colour.
 * [ArtPart.SLOT] answers the lit colour and [ArtPart.SLOT_TOP] the lit colour shaded by SLOT_TOP. This
 * function does not know the lit flag, so an unlit slot or strip is drawn with [slotColor] instead.
 */
internal fun faceColor(part: ArtPart, look: TileLook): Int = when (part) {
    ArtPart.TRIGGER_BODY, ArtPart.HEAD_BODY, ArtPart.CORD_BODY, ArtPart.WELL -> bodyColor(look)
    ArtPart.TRIGGER_FACE -> shade(bodyColor(look), ArtFactors.TRIGGER_FACE)
    ArtPart.TRIGGER_STRIPE -> shade(bodyColor(look), ArtFactors.TRIGGER_STRIPE)
    ArtPart.LOWER_LEFT -> shade(bodyColor(look), ArtFactors.LOWER_LEFT)
    ArtPart.LOWER_RIGHT -> shade(bodyColor(look), ArtFactors.LOWER_RIGHT)
    ArtPart.FRAME_TOP -> shade(look.glyphOutline, ArtFactors.FRAME_TOP)
    ArtPart.FRAME_LEFT -> shade(look.glyphOutline, ArtFactors.FRAME_LEFT)
    ArtPart.FRAME_BOTTOM -> shade(look.glyphOutline, ArtFactors.FRAME_BOTTOM)
    ArtPart.FRAME_RIGHT -> shade(look.glyphOutline, ArtFactors.FRAME_RIGHT)
    ArtPart.LEDGE_EDGE -> shade(bodyColor(look), ArtFactors.LEDGE_EDGE)
    ArtPart.LEDGE_FACE -> shade(look.glyphOutline, ArtFactors.LEDGE_FACE)
    ArtPart.LEDGE_END_LEFT, ArtPart.LEDGE_END_RIGHT -> shade(look.glyphOutline, ArtFactors.LEDGE_END)
    ArtPart.SLOT -> look.litSegment
    ArtPart.SLOT_TOP -> shade(look.litSegment, ArtFactors.SLOT_TOP)
    ArtPart.LETTERS -> look.glyph
    ArtPart.CANCEL_CROSS -> look.danger
    ArtPart.SEND_CHECK -> look.primary
}

/**
 * The colour of one slot of the meter, in either state. [lit] picks the lit or the unlit segment colour of
 * [look]; [top] is the strip over the slot, which is that colour shaded by [ArtFactors.SLOT_TOP].
 */
internal fun slotColor(top: Boolean, lit: Boolean, look: TileLook): Int {
    val base = if (lit) look.litSegment else look.unlitSegment
    return if (top) shade(base, ArtFactors.SLOT_TOP) else base
}

/** The alpha of the glow layer [layer] (0 innermost): 0.45, 0.25 and 0.12, and 0 for any other layer. */
internal fun glowAlpha(layer: Int): Float = when (layer) {
    0 -> 0.45f
    1 -> 0.25f
    2 -> 0.12f
    else -> 0f
}
