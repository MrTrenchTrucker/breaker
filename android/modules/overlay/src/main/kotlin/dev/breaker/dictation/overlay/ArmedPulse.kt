package dev.breaker.dictation.overlay

import android.view.animation.LinearInterpolator
import android.animation.ValueAnimator

/**
 * The one clock of the armed ring: a platform animator that drives the ring's alpha from [armedPulseAlpha].
 *
 * It is the only file of the module that names an animator. It runs on the main (UI) calls it is given,
 * holds no other clock, and changes nothing but the alpha that [onAlpha] receives. [start] and [stop]
 * are idempotent, and [stop] always leaves the ring at full strength.
 *
 * @param onAlpha receives the alpha of each frame; the view stores it and redraws.
 */
internal class ArmedPulse(private val onAlpha: (Float) -> Unit) {

    private var animator: ValueAnimator? = null

    /** True from [start] until [stop]. */
    val running: Boolean
        get() = animator != null

    /** Begin the cycle; does nothing when it already runs. The first frame is at full strength. */
    fun start() {
        if (running) return
        val next = ValueAnimator.ofFloat(PULSE_PHASE_START, PULSE_PHASE_END)
        next.duration = PULSE_PERIOD_MS
        next.interpolator = LinearInterpolator()
        next.repeatCount = ValueAnimator.INFINITE
        next.addUpdateListener { frame -> onAlpha(armedPulseAlpha(frame.animatedValue as Float)) }
        animator = next
        next.start()
    }

    /** End the cycle if it runs, then set the ring back to full strength. */
    fun stop() {
        animator?.cancel()
        animator = null
        onAlpha(1f)
    }

    companion object {
        /** Whether the system lets animations run at all; with the animator scale at zero it does not. */
        fun animationsOn(): Boolean = ValueAnimator.areAnimatorsEnabled()
    }
}
