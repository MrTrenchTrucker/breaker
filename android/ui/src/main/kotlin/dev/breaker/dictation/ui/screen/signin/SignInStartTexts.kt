package dev.breaker.dictation.ui.screen.signin

/*
 * The words the sign-in start screen shows, in plain ASCII.
 *
 * Every sentence a user reads on this screen is one of these constants, or the server address the app
 * already holds, shown after the word Server. None of them names a code, a key or a link.
 */

internal object SignInStartTexts {

    /** The one control: it opens the website in the browser. */
    const val ACTION = "Sign in with the website"

    /** The hint under the control, which says what happens next. */
    const val HINT = "You sign in on the website, then come back to Breaker."

    /** The server line when no server address is set. */
    const val NO_SERVER_LINE = "No server set"

    /** The state line while the browser is being opened. */
    const val OPENING = "Opening the browser"

    /** The state line while the website is open and the return from it is awaited. */
    const val WAITING = "Waiting for the website"

    /** The state line when no server address is set. */
    const val REFUSED_NO_SERVER = "Set the server address first"

    /** The state line when the server address cannot be opened. */
    const val REFUSED_BAD_ADDRESS = "That server address cannot be opened"

    /** The state line when the person comes back before the sign-in is finished. */
    const val RETURNED_NOT_FINISHED = "Back from the website. Sign-in is not finished yet."

    /** The state line when the sign-in is finished. */
    const val RETURNED_FINISHED = "Signed in"

    /** The server line: the address with its outer spaces removed, or the no-server line when it is blank. */
    fun serverLine(server: String): String {
        val trimmed = server.trim()
        return if (trimmed.isEmpty()) NO_SERVER_LINE else "Server: " + trimmed
    }

    /** The state line for [state], or null when the state shows none. */
    fun stateLine(state: SignInStartState): String? = when (state) {
        SignInStartState.Idle -> null
        SignInStartState.Opening -> OPENING
        SignInStartState.Waiting -> WAITING
        SignInStartState.RefusedNoServer -> REFUSED_NO_SERVER
        SignInStartState.RefusedAddress -> REFUSED_BAD_ADDRESS
        SignInStartState.ReturnedNotFinished -> RETURNED_NOT_FINISHED
        SignInStartState.ReturnedFinished -> RETURNED_FINISHED
    }
}
