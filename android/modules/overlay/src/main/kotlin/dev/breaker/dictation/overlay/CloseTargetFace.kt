package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.TruckingPalette

/**
 * The colours the close target box is drawn with, as opaque ARGB ints. Every colour is taken from the
 * palette it is built from, through the shade helper and the named factors of [CloseTargetLook].
 *
 * [fill] is the box fill, [edge] the idle edge, [edgeActive] the edge while the tile is over the target,
 * [truck] the silhouette, [load] the strapped X and [loadActive] the X while the tile is over the target.
 * No colour value is written here.
 */
internal data class CloseTargetFace(
    val fill: Int,
    val edge: Int,
    val edgeActive: Int,
    val truck: Int,
    val load: Int,
    val loadActive: Int,
) {
    companion object {
        /** The face for [palette]: the surface lightened, the text darkened, and the danger colour and its lightened form. */
        fun from(palette: TruckingPalette): CloseTargetFace = CloseTargetFace(
            fill = shade(palette.surface.argb, CloseTargetLook.FILL_LIGHTEN),
            edge = palette.textMuted.argb,
            edgeActive = palette.danger.argb,
            truck = shade(palette.text.argb, CloseTargetLook.TRUCK_SHADE),
            load = palette.danger.argb,
            loadActive = shade(palette.danger.argb, CloseTargetLook.LOAD_ACTIVE_LIGHTEN),
        )
    }
}
