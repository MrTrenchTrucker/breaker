package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.TruckingPalette
import kotlin.math.floor

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
     * danger after a failure, and the palette's sent or warning colour after a send. Everything else
     * is the same in every state.
     */
    fun look(state: TileState, palette: TruckingPalette): TileLook = TileLook(
        background = palette.surface.argb,
        glyph = palette.primary.argb,
        glyphOutline = palette.trim.argb,
        ring = ringColor(state, palette),
        litSegment = palette.primary.argb,
        unlitSegment = palette.bg.argb,
        control = palette.text.argb,
    )
}

/** The lowest alpha of the armed ring's pulse, and the highest. The minimum is below the maximum and both are in (0, 1]. */
internal const val PULSE_ALPHA_MIN = 0.25f
internal const val PULSE_ALPHA_MAX = 1f

/**
 * The ring colour of [state] in [palette], with [alpha] (0 to 1) applied to the armed ring only.
 * Every other state keeps its palette colour whatever [alpha] is.
 */
internal fun ringColor(state: TileState, palette: TruckingPalette, alpha: Float = 1f): Int {
    val ring = when (state) {
        TileState.IDLE -> palette.trim.argb
        TileState.ARMED -> palette.primary.argb
        TileState.RECORDING -> palette.primary.argb
        TileState.SENDING -> palette.primary.argb
        TileState.FAILED -> palette.danger.argb
        TileState.SENT -> palette.sent.argb
        TileState.SENT_LOCAL -> palette.warning.argb
    }
    return if (state == TileState.ARMED) withAlpha(ring, alpha) else ring
}

/**
 * The alpha of the armed ring at [phase], a position in one cycle of the pulse.
 *
 * The phase wraps: it is taken modulo 1, so 0 and 1 are the same moment and 0.5 is the peak. The alpha
 * rises in a straight line from [PULSE_ALPHA_MIN] at phase 0 to [PULSE_ALPHA_MAX] at phase 0.5 and falls
 * back the same way. A phase that is not a finite number gives [PULSE_ALPHA_MAX].
 */
internal fun armedPulseAlpha(phase: Float): Float {
    if (!phase.isFinite()) return PULSE_ALPHA_MAX
    val cycle = phase - floor(phase)
    val rise = if (cycle < 0.5f) 2f * cycle else 2f * (1f - cycle)
    return PULSE_ALPHA_MIN + (PULSE_ALPHA_MAX - PULSE_ALPHA_MIN) * rise
}

/** [argb] with its alpha byte set to [alpha] (0 to 1, taken to the nearest of 256 steps); the colour is kept. */
private fun withAlpha(argb: Int, alpha: Float): Int {
    val byte = (alpha.coerceIn(0f, 1f) * 255f + 0.5f).toInt()
    return (byte shl 24) or (argb and ((1 shl 24) - 1))
}
