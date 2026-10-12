package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.TruckingPalette

/**
 * The colours the close target is drawn with, as opaque ARGB ints.
 *
 * [surface] fills the circle, [danger] draws the X, [trim] draws the ring. Every colour comes
 * from the palette; no colour value is written here.
 */
internal data class CloseTargetFace(
    val surface: Int,
    val danger: Int,
    val trim: Int,
) {
    companion object {
        /** The face for [palette]. */
        fun from(palette: TruckingPalette) = CloseTargetFace(
            surface = palette.surface.argb,
            danger = palette.danger.argb,
            trim = palette.trim.argb,
        )
    }
}
