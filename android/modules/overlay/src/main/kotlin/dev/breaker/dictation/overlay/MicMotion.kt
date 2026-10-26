package dev.breaker.dictation.overlay

/**
 * The Home mic's glow has three motions and one stillness, and this is the one place that decides between them.
 *
 * [BREATHE]: dictation is on and ready, and the mic is not recording. A very slow breathing pulse; [ready] is a seam for tests, and the live signal is `ModelReady.isReady()`.
 * [LEVEL_PULSE]: the mic is recording, so the glow follows the live level instead of a fixed cycle.
 * [STEADY]: no motion, at the strength the state itself asks for. The Home screen is not visible, or the system
 *   does not let animations run, or the state is one that carries no motion of its own.
 * [DIM_STEADY]: dictation is off. Dim and still, and the only state that dims.
 *
 * The caller passes every input in, including whether the system lets animations run, so this function reads no
 * platform type and no clock and keeps no state. The view that draws the glow asks it on every push and redraws
 * from the answer; nothing else decides a motion.
 */
internal enum class MicMotion { BREATHE, LEVEL_PULSE, STEADY, DIM_STEADY }

/**
 * The motion the Home mic's glow shows for one push, in the order the decisions must be taken.
 *
 * A switched-off mic is the only one that dims, and it beats every other input, so its test comes first. Next the
 * motion stops when the Home screen is not visible or the system does not let animations run; those keep the state's
 * own strength, so they answer [MicMotion.STEADY] and not [MicMotion.DIM_STEADY]. Then recording follows the live
 * level, and a ready armed mic breathes. Every other state, and an armed mic that is not ready yet, holds at its own
 * strength and does not move. The shipped face passes [ready] as "armed", so it is never false in production.
 *
 * [animationsOn] is the caller's to pass in, which is why this function reads no platform type: the view asks the
 * system and hands the answer over.
 */
internal fun micMotion(state: TileState, switchOn: Boolean, ready: Boolean, visible: Boolean, animationsOn: Boolean): MicMotion = when {
    !switchOn -> MicMotion.DIM_STEADY
    !visible || !animationsOn -> MicMotion.STEADY
    state == TileState.RECORDING -> MicMotion.LEVEL_PULSE
    state == TileState.ARMED && ready -> MicMotion.BREATHE
    else -> MicMotion.STEADY
}

/** Whether dictation is switched on for [state]: [TileState.IDLE] is the switched-off state, so every other state is on. */
internal fun switchOnFor(state: TileState): Boolean = state != TileState.IDLE
