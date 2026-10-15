package dev.breaker.dictation.ui.controls

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * The shade factors of the controls, one per shading the art asks for. A factor above 0 moves the base
 * colour toward white by that share of the distance to white, a factor below 0 moves it toward black by
 * its size, and 0 keeps it.
 */
internal object ControlsFactors {
    const val LIT_LINE: Float = 0.30f     // the lit top line of the raised face, toward white
    const val LIP: Float = -0.35f         // the darker lip under the raised face, toward black
    const val NEUTRAL_LIP: Float = -0.25f // the lip under the neutral face, toward black
    const val LIP_DP: Float = 4f          // lip height; 6 px at 1.5 px per dp
    const val LIT_DP: Float = 2f          // lit line height; 3 px
    const val PRESS_DP: Float = 4f        // pressed = pushed down by the lip
    const val CORNER_DP: Float = 4f       // radius; the style guide caps corners at 4 dp
    const val SLANT_DEG: Float = 12f      // the step marks' slant
    const val TOUCH_MIN_DP: Float = 48f   // the touch-target floor
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
 * The WCAG contrast ratio between two opaque colours, 1.0 to 21.0. Used to prove a label on a
 * filled face stays readable.
 */
internal fun contrast(foreground: Int, background: Int): Double {
    val lighter = maxOf(luminance(foreground), luminance(background))
    val darker = minOf(luminance(foreground), luminance(background))
    return (lighter + 0.05) / (darker + 0.05)
}

private fun luminance(color: Int): Double {
    fun channel(value: Int): Double {
        val scaled = value / 255.0
        return if (scaled <= 0.03928) scaled / 12.92 else ((scaled + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * channel((color shr 16) and 0xFF) +
        0.7152 * channel((color shr 8) and 0xFF) +
        0.0722 * channel(color and 0xFF)
}
