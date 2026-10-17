package dev.breaker.dictation.ui.screen.firstrun

import kotlin.math.abs

/*
 * Whether a drag moves the page on.
 *
 * A right-to-left drag moves the page on, as the Next button does. A drag counts only when
 * it is long enough, measured in density-independent pixels, and mostly sideways, so a tap,
 * a scroll, a short wobble or a diagonal drag does not turn the page.
 */

/** The swipe rule: how long and how sideways a drag must be. */
internal object FirstRunSwipe {
    /** The shortest sideways drag that counts, in density-independent pixels. */
    const val MIN_DISTANCE_DP: Float = 64f

    /** How many times longer than the vertical movement the sideways movement must be. */
    const val HORIZONTAL_RATIO: Float = 2f

    /**
     * Whether a drag of [dxDp] and [dyDp] moves the page on. [dxDp] is negative when the
     * finger moved left; the vertical movement counts in either direction.
     */
    fun isAdvance(dxDp: Float, dyDp: Float): Boolean {
        val leftward = -dxDp
        return leftward >= MIN_DISTANCE_DP && leftward >= HORIZONTAL_RATIO * abs(dyDp)
    }
}
