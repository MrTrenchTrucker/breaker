package dev.breaker.dictation.ui.screen.signin

/*
 * The sign-in start screen's memory, and the one place its tap is turned into state.
 *
 * A tap opens the website only when no sign-in is already in flight: in Opening and Waiting a tap does
 * nothing, so a second browser is never opened over the first. The state is set before the callback
 * runs, so a state the callback sets is the one kept. The refusal and return mappings name the state
 * each answer shows.
 */

internal class SignInStartIntentHandler(private val onStart: () -> Unit) {

    /** The state as it stands now. */
    private var state: SignInStartState = SignInStartState.Idle

    /** The state as it stands now. */
    fun current(): SignInStartState = state

    /** A tap on the control: starts a sign-in unless one is in flight, and returns the state after it. */
    fun tap(): SignInStartState {
        if (state == SignInStartState.Opening || state == SignInStartState.Waiting) {
            return state
        }
        state = SignInStartState.Opening
        onStart()
        return state
    }

    /** Sets the state the control was told to show. */
    fun change(next: SignInStartState) {
        state = next
    }

    /** The state a refusal shows: the no-server state when no server is set, otherwise the bad-address state. */
    fun refusalState(noServer: Boolean): SignInStartState =
        if (noServer) SignInStartState.RefusedNoServer else SignInStartState.RefusedAddress

    /** The state a return shows: the finished state when the sign-in is finished, otherwise the not-finished state. */
    fun returnState(finished: Boolean): SignInStartState =
        if (finished) SignInStartState.ReturnedFinished else SignInStartState.ReturnedNotFinished

    override fun toString(): String = "SignInStartIntentHandler"
}
