package dev.breaker.dictation.overlay

/** The length of one full cycle of the armed ring's pulse, in milliseconds. */
internal const val PULSE_PERIOD_MS: Long = 1600L

/** The phase the pulse starts at: the peak, so arming looks like the steady ring and no jump happens. */
internal const val PULSE_PHASE_START: Float = 0.5f

/** The phase the pulse ends its first run at, one full cycle after the start. */
internal const val PULSE_PHASE_END: Float = 1.5f

/** The length of one full cycle of the busy ring's pulse, in milliseconds. Slower than the armed pulse. */
internal const val BUSY_PULSE_PERIOD_MS: Long = 3200L

/** The lowest alpha of the busy ring's pulse. Lower depth than the armed pulse (0.25). */
internal const val BUSY_PULSE_ALPHA_MIN: Float = 0.6f

/** The period of the Home mic's breathing motion: about five and a half seconds, one slow breath. */
internal const val BREATHING_PERIOD_MS: Long = 5500L

/**
 * The phase the Home mic's breathing cycle is started at: the peak of the breath, so the first frame it draws is
 * at full strength ([PULSE_ALPHA_MAX]) and the glow never fades in from its floor. The animator itself keeps its
 * 0..1 loop, and is started at this fraction: every later loop therefore still walks the whole breath, and the join
 * stays continuous because phase 1 and phase 0 both hold the same floor.
 */
internal const val BREATHING_PHASE_START: Float = 0.5f

/**
 * The lowest alpha the level glow is drawn at: the same floor the busy ring's pulse uses
 * ([BUSY_PULSE_ALPHA_MIN]), so the level glow and the busy ring agree on how dim "dim" is. A quiet room
 * therefore never dims the glow to a blink, and a loud syllable takes it all the way up.
 */
internal const val LEVEL_ALPHA_MIN: Float = 0.6f

/**
 * The alpha the level glow is drawn at for one smoothed level, [levelAlpha]: [alphaMin] at silence, full strength at
 * the top, and the two joined in a straight line between, so the glow lifts in proportion to the level. A smoothed
 * level that is not a finite number is drawn at full strength. A finite smoothed level is held to 0 to 1 first, so a
 * level outside that range reads as one end of the scale rather than as a brighter or a dimmer glow than the scale
 * has.
 */
internal fun levelAlpha(smoothed: Float, alphaMin: Float = LEVEL_ALPHA_MIN): Float {
    if (!smoothed.isFinite()) return PULSE_ALPHA_MAX
    return alphaMin + (PULSE_ALPHA_MAX - alphaMin) * smoothed.coerceIn(0f, 1f)
}

/**
 * The colour the armed ring is drawn with at [pulseAlpha]: [color] with its own alpha byte multiplied by the
 * pulse alpha, the product rounded half up, and its low 24 colour bits kept as they are. A finite pulse alpha
 * is held to 0 to 1 first; a pulse alpha that is not finite (NaN or an infinity) leaves the colour unchanged.
 */
internal fun ringDrawColor(color: Int, pulseAlpha: Float): Int {
    if (!pulseAlpha.isFinite()) return color
    val factor = pulseAlpha.coerceIn(0f, 1f)
    val alpha = ((color ushr 24) * factor + 0.5f).toInt()
    return (alpha shl 24) or (color shl 8 ushr 8)
}

/**
 * The colour one glow layer is drawn with: [color] at the pulse alpha times the layer alpha, by the same rule as
 * [ringDrawColor]. A pulse alpha that is not finite counts as full strength. A layer alpha that is not finite
 * gives no alpha at all; a finite layer alpha is held to 0 to 1 first.
 */
internal fun glowDrawColor(color: Int, pulseAlpha: Float, layerAlpha: Float): Int {
    if (!layerAlpha.isFinite()) return ringDrawColor(color, 0f)
    val pulse = if (pulseAlpha.isFinite()) pulseAlpha else 1f
    return ringDrawColor(color, pulse * layerAlpha.coerceIn(0f, 1f))
}

/** The app wants a pulse: only an ARMED tile that we show. */
internal fun pulseWanted(state: TileState, shown: Boolean): Boolean = state == TileState.ARMED && shown

/** Run only when all five hold. */
internal fun pulseShouldRun(
    wanted: Boolean,
    attached: Boolean,
    visible: Boolean,
    screenOn: Boolean,
    animationsOn: Boolean,
): Boolean = wanted && attached && visible && screenOn && animationsOn
