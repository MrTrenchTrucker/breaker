package dev.breaker.dictation.ui

/**
 * The switch that turns Breaker on and off, as the app offers it to the setup screen.
 *
 * The app implements this over whatever keeps the microphone running, and gives
 * the implementation to [createOnboardingView]. This module never learns how Breaker
 * is switched on; it only asks, and shows the answer. Nothing about the switch is
 * kept here: whether Breaker is on is read from [isOn] each time the screen is
 * drawn, so a switch that was turned off elsewhere is shown as off.
 *
 * Every call comes from the main thread. An implementation is expected not to throw,
 * and the screen still guards each call, so a failure shows as a plain sentence
 * instead of closing the app.
 */
interface BreakerSwitch {
    /** True while Breaker is switched on. */
    fun isOn(): Boolean

    /**
     * Switches Breaker on, and says what came of it.
     *
     * Once this returns [Result.On] or [Result.AlreadyOn], [isOn] answers true.
     */
    fun switchOn(): Result

    /** Switches Breaker off. Safe to call when it is already off. */
    fun switchOff()

    /** What switching Breaker on came to. */
    sealed class Result {
        /** Breaker was off and is now on. */
        object On : Result()

        /** Breaker was already on; nothing changed. */
        object AlreadyOn : Result()

        /**
         * Breaker could not be switched on.
         *
         * @property sentence a plain sentence, written by the app, that says why.
         */
        class Refused(val sentence: String) : Result()
    }
}
