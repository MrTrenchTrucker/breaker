package dev.breaker.dictation.ui.screen.auth

/*
 * The words the auth screen shows, in plain ASCII.
 *
 * Every sentence a user reads on this screen is one of these constants; there are no process
 * words and no other text drawn here beyond the typed username, which the app controls. These feed
 * the words-for-approval table; none of them is marked approved here.
 */

internal object AuthTexts {

    /** The label of the username field. */
    const val USERNAME_LABEL = "Username"

    /** The caption shown over the masked password field, with no typed value in it. */
    const val PASSWORD_CAPTION = "Password"

    /** The submit control, for signing in. */
    const val SUBMIT_LOGIN = "Sign in"

    /** The submit control, for creating an account. */
    const val SUBMIT_REGISTER = "Create account"

    /** The login face of the two-way choice control. */
    const val FACE_LOGIN = "Login"

    /** The register face of the two-way choice control. */
    const val FACE_REGISTER = "Register"

    /** The idle caption: neither a submit nor a refusal is shown yet. */
    const val IDLE = "Sign in or create an account"

    /** The refused line, shown when the password is too short. */
    const val REFUSED = "Too short"

    /** The warning line, shown while a weak password stays submitted. */
    const val WEAK_WARNING = "Weak password"

    /** The busy caption shown while a submit is in flight. */
    const val BUSY = "Working"

}
