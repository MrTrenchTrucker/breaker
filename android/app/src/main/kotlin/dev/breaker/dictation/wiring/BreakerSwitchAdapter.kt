package dev.breaker.dictation.wiring

import dev.breaker.dictation.ui.BreakerSwitch

/**
 * The app's implementation of the ui module's [BreakerSwitch], delegating to [ArmedSwitch].
 *
 * Maps the app's [SwitchOn] answers to the ui module's [BreakerSwitch.Result] answers.
 * The app owns the switch; the ui module only asks.
 */
class BreakerSwitchAdapter(
    private val armedSwitch: ArmedSwitch,
) : BreakerSwitch {
    override fun isOn(): Boolean = armedSwitch.isOn()

    override fun switchOn(): BreakerSwitch.Result =
        when (val answer = armedSwitch.switchOn()) {
            is SwitchOn.On -> BreakerSwitch.Result.On
            is SwitchOn.AlreadyOn -> BreakerSwitch.Result.AlreadyOn
            is SwitchOn.Refused -> BreakerSwitch.Result.Refused(answer.sentence)
        }

    override fun switchOff() = armedSwitch.switchOff()
}
