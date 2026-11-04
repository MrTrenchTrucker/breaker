package dev.breaker.dictation.wiring

import dev.breaker.dictation.ui.SignInStartControl

/**
 * The glue between the start control and the sign-in wiring. The browser opener and the server
 * address are passed in, so this class needs no Android type and no view of its own.
 */
class SignInStartScreen(
    private val wiring: SignInStartWiring,
    private val control: SignInStartControl,
    private val openBrowser: (String) -> Boolean,
    private val serverUrl: () -> String,
) {

    /** A tap on the start control: start a sign-in and show what happened. */
    fun onTap() {
        when (val outcome = wiring.start(serverUrl())) {
            is SignInStartOutcome.Open -> {
                control.opening()
                if (openBrowser(outcome.url)) {
                    control.waiting()
                } else {
                    wiring.cancel()
                    control.refused(false)
                }
            }
            SignInStartOutcome.NoServer -> control.refused(true)
            SignInStartOutcome.BadAddress -> control.refused(false)
        }
    }

    /** The screen came back to the front: show a finished or unfinished return, otherwise nothing. */
    fun onResume(state: SignInReturnState?) {
        when (state) {
            SignInReturnState.NOT_FINISHED -> control.returned(false)
            SignInReturnState.TAKEN -> control.returned(true)
            SignInReturnState.IGNORED, SignInReturnState.NO_ATTEMPT, null -> Unit
        }
    }
}
