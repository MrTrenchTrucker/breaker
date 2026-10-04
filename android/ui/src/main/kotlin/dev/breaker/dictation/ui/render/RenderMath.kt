package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.screen.Emphasis
import dev.breaker.dictation.ui.theme.PaletteSlot
import kotlin.math.ceil

/*
 * The decisions the renderer takes, as plain functions.
 *
 * This file has no Android import, so the rules below run in a JVM unit test.
 * The renderer calls them and adds nothing of its own.
 */

/**
 * The size in pixels of [dp] density-independent pixels on a screen of [density].
 *
 * The result is rounded up, so it is never below `dp * density`: a touch target
 * of 48 dp stays at least 48 dp on a screen whose density is not a whole number.
 * The product is taken in double precision from the two float inputs, so the
 * result is exact for the values it is given.
 */
internal fun touchTargetPx(dp: Float, density: Float): Int =
    ceil(dp.toDouble() * density.toDouble()).toInt()

/**
 * The palette slot an action paints its text in.
 *
 * A disabled action is drawn in the muted text colour whatever its emphasis, so
 * it reads as unavailable. An enabled action takes the colour of its emphasis:
 * primary, plain text, or danger. Each of these colours reaches the contrast
 * minimum on the surface slot, which is the fill every action is drawn on.
 */
internal fun actionTextSlot(emphasis: Emphasis, enabled: Boolean): PaletteSlot {
    if (!enabled) return PaletteSlot.TEXT_MUTED
    return when (emphasis) {
        Emphasis.PRIMARY -> PaletteSlot.PRIMARY
        Emphasis.SECONDARY -> PaletteSlot.TEXT
        Emphasis.DESTRUCTIVE -> PaletteSlot.DANGER
    }
}
