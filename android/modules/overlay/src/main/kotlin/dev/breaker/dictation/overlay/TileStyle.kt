package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.TruckingPalette
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.PI

/**
 * The colours one tile state is drawn with, as opaque ARGB ints.
 *
 * [background] is the surface colour: the halo under the notice text only is drawn in it, and the body colour is chosen between it and [control]; nothing is filled with it. [glyph] and [glyphOutline] colour the microphone picture (the letters at a 12 degree slant from the brand art). [ring] is the
 * glowing outline along the edge of the microphone. [litSegment] and [unlitSegment] are the meter's six slots in the grille that are on and
 * off; an off segment has the colour of the page background, so it differs from an on segment by
 * lightness and not by hue alone. The Home mic's halo is drawn in [halo], which is the primary against a dark
 * page and the primary deepened against a light page, so the fainter outer edge of the halo still reads. The recording buttons read [danger]/[primary] with a [glyphOutline] outline around each shape, no glow.
 */
internal data class TileLook(
    val background: Int,
    val glyph: Int,
    val glyphOutline: Int,
    val danger: Int,
    val primary: Int,
    val busyOutline: Int,
    val ring: Int,
    val litSegment: Int,
    val halo: Int,
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
     * Only the ring depends on the state: trim when idle, primary when armed and recording, warning
     * while sending, danger after a failure, and the palette's sent or warning colour after a send.
     * Everything else is the same in every state.
     */
    fun look(state: TileState, palette: TruckingPalette): TileLook = TileLook(
        background = palette.surface.argb,
        glyph = palette.primary.argb,
        glyphOutline = palette.trim.argb,
        danger = palette.danger.argb,
        primary = palette.primary.argb,
        busyOutline = shade(palette.danger.argb, ArtFactors.BUSY_OUTLINE_SHADE),
        ring = ringColor(state, palette),
        litSegment = palette.primary.argb,
        halo = haloColor(palette),
        unlitSegment = palette.bg.argb,
        control = palette.text.argb,
    )

    /**
     * The Home mic's halo colour: the primary against a dark page, and the primary deepened against a
     * light page, so the fainter outer edge of the halo still reads. Derived, never a hex.
     */
    private fun haloColor(palette: TruckingPalette): Int {
        val shade = if (relativeLuminance(palette.bg.argb) > ArtFactors.LIGHT_PAGE) ArtFactors.HALO_LIGHT_SHADE else 0f
        return shade(palette.primary.argb, shade)
    }
}

/** The lowest alpha of the armed ring's pulse, and the highest. The minimum is below the maximum and both are in (0, 1]. */
internal const val PULSE_ALPHA_MIN = 0.25f
internal const val PULSE_ALPHA_MAX = 1f

/** The lowest alpha of the Home mic's breathing motion. A small swing: the glow moves between this and the maximum. */
internal const val BREATHING_ALPHA_MIN = 0.75f

/**
 * The two factors of [levelSmoothStep], per call at about 15 calls a second: the larger, [LEVEL_ATTACK], when the
 * level rises, the smaller, [LEVEL_RELEASE], when it falls. The call rate is the level meter's: one update is
 * 1067 samples at 16 kHz, about 15 a second. The factors are the parts of the smoother; the time constants are
 * stated here so the numbers mean something.
 *
 * A smoothed level that follows the app's level by one factor a call closes the gap to a fraction f of the old gap
 * each call, so its time constant is the time per call divided by minus the natural log of the fraction that
 * survives: -dt / ln(1 - f). At 15 calls a second that is -0.0667 s / ln(0.65), about 0.19 s for [LEVEL_ATTACK],
 * and -0.0667 s / ln(0.92), about 0.83 s for [LEVEL_RELEASE].
 */
internal const val LEVEL_ATTACK: Float = 0.35f
internal const val LEVEL_RELEASE: Float = 0.08f

/**
 * The ring colour of [state] in [palette], as the palette has it. The armed ring's pulse alpha is not
 * applied here; the view applies it when it draws the ring, through [ringDrawColor].
 */
internal fun ringColor(state: TileState, palette: TruckingPalette): Int {
    val ring = when (state) {
        TileState.IDLE -> palette.trim.argb
        TileState.ARMED -> palette.primary.argb
        TileState.RECORDING -> palette.primary.argb
        TileState.SENDING -> palette.warning.argb
        TileState.FAILED -> palette.danger.argb
        TileState.SENT -> palette.sent.argb
        TileState.SENT_LOCAL -> palette.warning.argb
        TileState.MIC_BUSY -> palette.danger.argb
    }
    return ring
}

/**
 * The alpha of the armed ring at [phase], a position in one cycle of the pulse.
 *
 * The phase wraps: it is taken modulo 1, so 0 and 1 are the same moment and 0.5 is the peak. The alpha
 * rises in a straight line from [PULSE_ALPHA_MIN] at phase 0 to [PULSE_ALPHA_MAX] at phase 0.5 and falls
 * back the same way. A phase that is not a finite number gives [PULSE_ALPHA_MAX].
 */
internal fun armedPulseAlpha(phase: Float, alphaMin: Float = PULSE_ALPHA_MIN): Float {
    if (!phase.isFinite()) return PULSE_ALPHA_MAX
    val cycle = phase - floor(phase)
    val rise = if (cycle < 0.5f) 2f * cycle else 2f * (1f - cycle)
    return alphaMin + (PULSE_ALPHA_MAX - alphaMin) * rise
}

/**
 * The attack/release smoother of the level the app publishes: one step of the level toward the new value.
 *
 * [previous] moves toward [level] by [LEVEL_ATTACK] when [level] is higher (a loud syllable lifts the glow
 * quickly) and by [LEVEL_RELEASE] when it is lower (the glow settles back gently). [previous] equal to
 * [level] gives that value, on either branch. A [level] that is not a finite number gives the new value
 * rather than poisoning the smoothed level, and a [previous] that is not finite starts the smoother at the
 * new value, so a stray value is shed instead of held.
 *
 * The factors are per call at about 15 calls a second, so [LEVEL_ATTACK] is a time constant near 0.19 s
 * and [LEVEL_RELEASE] near 0.83 s. A [level] outside 0 to 1 is not held here: the smoother is arithmetic
 * over the caller's values, and [levelAlpha] holds its own input.
 */
internal fun levelSmoothStep(previous: Float, level: Float): Float {
    if (!level.isFinite()) return level
    if (!previous.isFinite()) return level
    val factor = if (level > previous) LEVEL_ATTACK else LEVEL_RELEASE
    return previous + (level - previous) * factor
}

/**
 * The alpha of the Home mic's glow at [phase], a position in one cycle of the breathing motion.
 *
 * The phase wraps: it is taken modulo 1, so 0 and 1 are the same moment and 0.5 is the peak. Unlike the armed ring,
 * which rises and falls in straight lines, this eases: the glow leaves [alphaMin] slowly, turns over at the peak and
 * settles back slowly, so the motion has no corner in it. A phase that is not a finite number gives
 * [PULSE_ALPHA_MAX].
 */
internal fun breathingAlpha(phase: Float, alphaMin: Float = BREATHING_ALPHA_MIN): Float {
    if (!phase.isFinite()) return PULSE_ALPHA_MAX
    val cycle = phase - floor(phase)
    val ease = (1f - cos(2f * PI.toFloat() * cycle)) / 2f
    return alphaMin + (PULSE_ALPHA_MAX - alphaMin) * ease
}
