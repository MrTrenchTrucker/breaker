package dev.breaker.dictation.ui.screen.auth

/*
 * The auth screen's memory, and the one place its taps are turned into state.
 *
 * A tap toggles the register face or asks the app to submit; the handler runs [AuthPolicy.rate]
 * on the typed password, updates the state machine, and calls the passed in exactly once per
 * submit. The submit is the only action the host acts on with input: its zero-fill happens in a
 * finally so both a returned and a thrown callback leave the char array all zero; every other tap
 * just redraws from the state held. Call it from one thread at a time: it holds no lock, but its
 * own state changes as fast as the taps come in.
 */

internal class AuthIntentHandler(
    private val onSubmit: (username: String, password: CharArray, register: Boolean) -> Unit,
) {

    /** The face currently chosen: create on one, sign in on the other. */
    private var register = false

    /** The last typed username, carried as text so the submit callback can hand it back. */
    var username = ""
        internal set

    private var state: AuthState = AuthState.Idle(register)

    /** The state as it stands now. */
    fun current(): AuthState = state

    /** Turns a tap on [nodeId] into the next state: either face or the submit control, or redrawn. */
    fun tap(nodeId: String): AuthState = when (nodeId) {
        AuthNodeIds.FACE_REGISTER -> {
            register = true
            state = AuthState.Idle(true)
            state
        }
        AuthNodeIds.FACE_LOGIN -> {
            register = false
            state = AuthState.Idle(false)
            state
        }
        else -> state
    }

    /**
     * Runs [AuthPolicy.rate] on [password], updates the state machine, and calls [onSubmit]
     * exactly once. A too-short password stays Refused with no callback; a weak or ok password
     * moves to Warning or Busy; either way the passed in char array is zero-filled afterwards.
     */
    fun submit(password: CharArray) {
        try {
            when (AuthPolicy.rate(password)) {
                AuthPolicy.Rating.TooShort -> {
                    state = AuthState.Refused(register, AuthTexts.REFUSED)
                }
                AuthPolicy.Rating.Weak -> {
                    state = AuthState.Warning(register, AuthTexts.WEAK_WARNING)
                    onSubmitOnce(username, password, register)
                }
                AuthPolicy.Rating.Ok -> {
                    state = AuthState.Busy(register)
                    onSubmitOnce(username, password, register)
                }
            }
        } finally {
            for (index in password.indices) {
                password[index] = '\u0000'
            }
        }
    }

    /**
     * Calls [onSubmit] once. The zero-fill of the passed in char array happens in the caller's
     * finally, whether this returns or throws, so no copy of the password can survive past submit.
     */
    private fun onSubmitOnce(username: String, password: CharArray, register: Boolean) {
        onSubmit(username, password, register)
    }

    /** Moves the state back to idle on the same face after a submit has finished. */
    fun submitted(): AuthState {
        state = AuthState.Idle(register)
        return state
    }

    /** The shake surface the current state carries, for the masked-then-shaken look. */
    fun shakeMs(): Long? = state.shakeMs

    override fun toString(): String = "AuthIntentHandler(register=$register)"
}
