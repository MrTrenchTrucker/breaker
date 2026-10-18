package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.controls.ControlsFactors
import dev.breaker.dictation.ui.controls.shade
import dev.breaker.dictation.ui.theme.PaletteSlot

/*
 * The arithmetic of the full-width layers, as plain functions.
 *
 * A host pads its content, and the rule and the stripes reach past that padding to both
 * edges of the screen. These functions say how wide such a layer is and how far it moves
 * left; the views only apply them. The soft stripe colour is the primary shaded by a named
 * factor, so no colour is written here.
 */

/** The width of a full-width layer: the parent's width plus the host's padding on both sides. */
internal fun bleedWidth(parentWidth: Int, padLeft: Int, padRight: Int): Int = parentWidth + padLeft + padRight

/** How far a full-width layer moves left, so that it starts at the edge of the screen. */
internal fun bleedShift(padLeft: Int): Int = -padLeft

/**
 * The scroll view a full-width layer bleeds through, as an index into [horizontalPaddings]: the left plus right
 * padding of each scroll view above the layer, nearest first. The first one with horizontal padding is used;
 * when none has, the outermost is used; with no scroll view above the layer the answer is null.
 */
internal fun bleedScrollIndex(horizontalPaddings: List<Int>): Int? {
    val chosen = horizontalPaddings.indexOfFirst { it > 0 }
    return when {
        chosen >= 0 -> chosen
        horizontalPaddings.isEmpty() -> null
        else -> horizontalPaddings.lastIndex
    }
}

/** Whether the stripe at [index] takes the primary colour; the others take the soft colour. */
internal fun isPrimaryStripe(index: Int): Boolean = index % (1 + 1) == 0

/** The soft stripe colour: the primary lightened in light mode and darkened in dark mode, by its named factor. */
internal fun softStripeColor(primaryArgb: Int, dark: Boolean): Int =
    shade(primaryArgb, if (dark) ControlsFactors.STRIPE_SOFT_FACTOR_DARK else ControlsFactors.STRIPE_SOFT_FACTOR_LIGHT)

/** Whether the plain stripe at [index] takes the hover colour: only the middle one of the three. */
internal fun isHoverStripe(index: Int): Boolean = index == 1

/** The palette slot of the plain stripe at [index]: the hover colour for the middle stripe, the primary colour for the others. */
internal fun plainStripeSlot(index: Int): PaletteSlot = if (isHoverStripe(index)) PaletteSlot.PRIMARY_HOVER else PaletteSlot.PRIMARY
