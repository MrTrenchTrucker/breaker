package dev.breaker.dictation.ui.controls

import kotlin.math.PI
import kotlin.math.tan

/**
 * The download bar's numbers, in dp: its size, its stripes, how far the stripes have moved,
 * and how full the fill is. Plain Kotlin with no platform name; the view draws what these
 * functions return.
 *
 * The art gives the stripes in px at 1.5 px per dp, and each constant names its px.
 */
internal object DownloadBarMath {

    /** The bar's height: 36 px. */
    const val BAR_HEIGHT_DP: Float = 24f

    /** The track's corner radius. */
    const val CORNER_DP: Float = 3f

    /** The track's outline thickness. */
    const val OUTLINE_DP: Float = 1.5f

    /** Half the outline thickness: the inset that keeps the outline stroke on the track's edge. */
    const val OUTLINE_INSET_DP: Float = OUTLINE_DP / 2f

    /** The stripes sit this far inside the track, at the top: 6 px. */
    const val STRIPE_INSET_DP: Float = 4f

    /** The stripes' lower edge: the bar's height less the same inset. */
    const val STRIPE_BOTTOM_DP: Float = BAR_HEIGHT_DP - STRIPE_INSET_DP

    /** Each stripe's width: 12 px. */
    const val STRIPE_WIDTH_DP: Float = 8f

    /** The gap between two stripes: 22 px. */
    const val STRIPE_GAP_DP: Float = 14.7f

    /** How fast the stripes move, left to right: about 119 px per second. */
    const val STRIPE_SPEED_DP_PER_S: Float = 80f

    /** One stripe and its gap: the distance from one stripe's left edge to the next. */
    val STRIPE_PERIOD_DP: Float = STRIPE_WIDTH_DP + STRIPE_GAP_DP

    /** Whether the total is unknown, which the bar shows as a striped track with no percent. */
    fun isIndeterminate(totalMb: Int): Boolean = totalMb <= 0

    /**
     * How much of the track the fill covers, from 0f to 1f. The checking state and an unknown
     * total fill the whole track; a negative amount done counts as nothing.
     */
    fun fillFraction(doneMb: Int, totalMb: Int, checking: Boolean): Float {
        if (checking || isIndeterminate(totalMb)) return 1f
        val done = if (doneMb < 0) 0 else doneMb
        return (done.toFloat() / totalMb.toFloat()).coerceIn(0f, 1f)
    }

    /**
     * The whole percent done, or null when the total is unknown. The whole part is taken, not the
     * nearest whole number. A preview drawn beside the art does take the nearest whole number; the
     * clamp here keeps the bar at 99 at most, so 100 is never shown before the check starts.
     */
    fun percent(doneMb: Int, totalMb: Int): Int? {
        if (isIndeterminate(totalMb)) return null
        val done = if (doneMb < 0) 0L else doneMb.toLong()
        val whole = (done * 100L / totalMb.toLong()).toInt()
        return whole.coerceIn(0, 99)
    }

    /**
     * How far the stripes have moved, in dp, from 0 up to one period. It is 0 when the phone's
     * animations are off or no time has passed; it grows with the time and wraps back to 0.
     */
    fun stripeOffsetDp(elapsedMs: Long, animate: Boolean): Float {
        if (!animate || elapsedMs <= 0L) return 0f
        val travelled = elapsedMs / 1000f * STRIPE_SPEED_DP_PER_S
        return travelled % STRIPE_PERIOD_DP
    }

    /**
     * The left edge of each stripe, before the slant, for the stripes that touch a fill
     * [fillWidthDp] wide. The first edge is one period before [offsetDp]; the edges step by one
     * period while they lie left of the fill's right edge. Empty when the fill is not a positive,
     * finite width.
     */
    fun stripeStarts(fillWidthDp: Float, offsetDp: Float): List<Float> {
        if (!fillWidthDp.isFinite() || fillWidthDp <= 0f) return emptyList()
        val first = offsetDp - STRIPE_PERIOD_DP
        val starts = ArrayList<Float>()
        var index = 0
        var edge = first
        while (edge < fillWidthDp) {
            starts.add(edge)
            index++
            edge = first + index * STRIPE_PERIOD_DP
        }
        return starts
    }

    /** The shift to the right that slants a stripe [heightDp] tall by the kit's own angle. */
    fun slantDp(heightDp: Float): Float =
        (tan(ControlsFactors.SLANT_DEG * PI / 180.0) * heightDp).toFloat()
}
