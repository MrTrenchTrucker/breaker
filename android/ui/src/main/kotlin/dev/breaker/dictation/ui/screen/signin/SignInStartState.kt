package dev.breaker.dictation.ui.screen.signin

/*
 * The sign-in start screen's state, as one of seven fixed names.
 *
 * No state carries data: a state never holds a server address, a code, a key or a link, and its
 * toString prints the name alone, so nothing this screen holds can reach a log or a text by accident.
 */

internal sealed class SignInStartState {

    /** Nothing has started: the control is ready to open the website. */
    object Idle : SignInStartState() {
        override fun toString(): String = "Idle"
    }

    /** The browser is being opened for the website. */
    object Opening : SignInStartState() {
        override fun toString(): String = "Opening"
    }

    /** The website is open and the return from it is awaited. */
    object Waiting : SignInStartState() {
        override fun toString(): String = "Waiting"
    }

    /** The sign-in was refused because no server address is set. */
    object RefusedNoServer : SignInStartState() {
        override fun toString(): String = "RefusedNoServer"
    }

    /** The sign-in was refused because the server address cannot be opened. */
    object RefusedAddress : SignInStartState() {
        override fun toString(): String = "RefusedAddress"
    }

    /** The person came back from the website before the sign-in was finished. */
    object ReturnedNotFinished : SignInStartState() {
        override fun toString(): String = "ReturnedNotFinished"
    }

    /** The person came back and the sign-in is finished. */
    object ReturnedFinished : SignInStartState() {
        override fun toString(): String = "ReturnedFinished"
    }
}
