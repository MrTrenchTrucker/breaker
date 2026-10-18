package dev.breaker.dictation.ui.testing

import dev.breaker.dictation.ui.BreakerSwitch

/**
 * A [BreakerSwitch] held in memory that counts every call and can be made to fail.
 *
 * [on] is what [isOn] returns; a test may set it as another part of the app would
 * switch Breaker on or off. [nextResult] is what [switchOn] answers: after [BreakerSwitch.Result.On]
 * or [BreakerSwitch.Result.AlreadyOn] the fake is on, after a refusal it is unchanged.
 * The three counters count every call, failed or not. While [failIsOn], [failSwitchOn]
 * or [failSwitchOff] is set the call throws an [IllegalStateException] whose message
 * holds [SETUP_FAILURE_MARKER], and the fake's state is left as it was.
 */
internal class FakeBreakerSwitch(var on: Boolean = false) : BreakerSwitch {
    var isOnCalls: Int = 0
        private set
    var switchOnCalls: Int = 0
        private set
    var switchOffCalls: Int = 0
        private set

    var nextResult: BreakerSwitch.Result = BreakerSwitch.Result.On
    var failIsOn: Boolean = false
    var failSwitchOn: Boolean = false
    var failSwitchOff: Boolean = false

    override fun isOn(): Boolean {
        isOnCalls++
        if (failIsOn) throw IllegalStateException("isOn failed: $SETUP_FAILURE_MARKER")
        return on
    }

    override fun switchOn(): BreakerSwitch.Result {
        switchOnCalls++
        if (failSwitchOn) throw IllegalStateException("switchOn failed: $SETUP_FAILURE_MARKER")
        val result = nextResult
        when (result) {
            BreakerSwitch.Result.On, BreakerSwitch.Result.AlreadyOn -> on = true
            is BreakerSwitch.Result.Refused -> Unit
        }
        return result
    }

    override fun switchOff() {
        switchOffCalls++
        if (failSwitchOff) throw IllegalStateException("switchOff failed: $SETUP_FAILURE_MARKER")
        on = false
    }
}
