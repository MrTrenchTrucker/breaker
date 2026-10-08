package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.TruckingPalette

/**
 * The colours one tile state is drawn with, as opaque ARGB ints.
 *
 * [background] fills the tile. [glyph] and [glyphOutline] colour the microphone picture. [ring] is the
 * ring around the microphone. [litSegment] and [unlitSegment] are the meter's segments that are on and
 * off; an off segment has the colour of the page background, so it differs from an on segment by
 * lightness and not by hue alone. [control] is the cancel and send buttons and the notice text.
 */
internal data class TileLook(
    val background: Int,
    val glyph: Int,
    val glyphOutline: Int,
    val ring: Int,
    val litSegment: Int,
    val unlitSegment: Int,
    val control: Int,
)

/**
 * Everything the view needs to draw the tile once: the state, the shape of the window, how many of
 * [segments] meter segments are lit, the colours, the app's notice text (null when there is none) and
 * the app's description of the tile for accessibility (null when there is none).
 */
internal data class TileFace(
    val state: TileState,
    val shape: TileShape,
    val litSegments: Int,
    val segments: Int,
    val look: TileLook,
    val notice: String?,
    val description: String?,
)

/** Maps a tile state to the palette colours it is drawn with. No colour is written here, only palette fields. */
internal object TileStyle {

    /**
     * The look of the tile in [state] with [palette].
     *
     * Only the ring depends on the state: trim when idle, primary when armed, recording and sending,
     * and danger after a failure. Everything else is the same in every state.
     */
    fun look(state: TileState, palette: TruckingPalette): TileLook = TileLook(
        background = palette.surface.argb,
        glyph = palette.primary.argb,
        glyphOutline = palette.trim.argb,
        ring = when (state) {
            TileState.IDLE -> palette.trim.argb
            TileState.ARMED -> palette.primary.argb
            TileState.RECORDING -> palette.primary.argb
            TileState.SENDING -> palette.primary.argb
            TileState.FAILED -> palette.danger.argb
        },
        litSegment = palette.primary.argb,
        unlitSegment = palette.bg.argb,
        control = palette.text.argb,
    )
}
