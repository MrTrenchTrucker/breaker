package dev.breaker.dictation.overlay

import android.animation.ValueAnimator

/**
 * The one clock of the Home mic's breathing glow: a platform animator that drives the glow's alpha from
 * [breathingAlpha].
 *
 * It is one of the two files of the module that names an animator, and it is the one that serves the Home mic. It runs
 * on the main (UI) thread it is called on, holds no other clock, and changes nothing but the alpha that [onAlpha]
 * receives. [start] and [stop] are idempotent, and [stop] always leaves the glow at full strength.
 *
 * The motion is slower and shallower than the armed ring's pulse: one breath takes [BREATHING_PERIOD_MS] and the glow
 * only travels from [BREATHING_ALPHA_MIN] to full strength, so it reads as a breath and not as a blink. [start] seeks
 * the cycle to [BREATHING_PHASE_START] first, so the first frame drawn is at full strength.
 *
 * @param onAlpha receives the alpha of each frame; the view stores it and redraws.
 */
internal class BreathingPulse(
    internal val periodMs: Long = BREATHING_PERIOD_MS,
    internal val alphaMin: Float = BREATHING_ALPHA_MIN,
    private val onAlpha: (Float) -> Unit,
) {

    private var animator: ValueAnimator? = null

    /** True from [start] until [stop]. */
    val running: Boolean
        get() = animator != null

    /** Begin the cycle; does nothing when it already runs. The first frame is at full strength. */
    fun start() {
        if (running) return
        val next = ValueAnimator.ofFloat(0f, 1f)
        next.duration = periodMs
        next.repeatCount = ValueAnimator.INFINITE
        next.addUpdateListener { frame -> onAlpha(breathingAlpha(frame.animatedValue as Float, alphaMin)) }
        // The loop stays 0..1 and repeats, so each join runs the floor against the floor and nothing snaps. Seeking
        // it to the peak first is what makes the first frame full strength rather than the floor.
        next.setCurrentFraction(BREATHING_PHASE_START)
        animator = next
        next.start()
    }

    /** End the cycle if it runs, then set the glow back to full strength. */
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
