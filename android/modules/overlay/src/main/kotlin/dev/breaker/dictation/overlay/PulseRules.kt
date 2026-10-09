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
