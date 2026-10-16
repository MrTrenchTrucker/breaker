package dev.breaker.dictation.ui.screen.firstrun

import dev.breaker.dictation.ui.BreakerSwitch

/*
 * The switch of the first run, which has none.
 *
 * The first run shows no on-off switch: Breaker is switched on by the normal app once the
 * first run has ended. The set-up handler still needs a switch to answer its taps, so this
 * object stands in for one. It answers the way a switch that cannot be changed answers, so a
 * tap that asks for it to be switched on is refused, and nothing is ever on.
 */

/** A switch that is always off and always refuses to switch on. */
internal object NoSwitch : BreakerSwitch {
    override fun isOn(): Boolean = false

    /** Refuses with a blank sentence, which the set-up handler replaces with its own fixed notice. */
    override fun switchOn(): BreakerSwitch.Result = BreakerSwitch.Result.Refused("")

    override fun switchOff() {}
}
