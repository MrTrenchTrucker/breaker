package dev.breaker.dictation.ui.screen.auth

/*
 * The auth screen's state machine, and the one place its surface carries a shake.
 *
 * No state or property here holds the typed password; every subtype carries only text that was
 * authored by the app (the fixed warning/refusal words), never a copy of the password. A Refused
 * or Warning also carries the 600 ms shake surface, so the host can drive the masked-then-shaken
 * look without a platform timer in this phase; the other states carry no shake at all.
 */

internal sealed class AuthState {

    /** Idle on one of the two faces: register (create) or login (sign in). */
    data class Idle(override val register: Boolean) : AuthState()

    /** The password was refused and stays refused until the app moves it back to idle. Carries the face chosen. */
    data class Refused(override val register: Boolean, val text: String) : AuthState()

    /** The password is weak but still submitted; it stays here until the app moves it back to idle. Carries the face chosen. */
    data class Warning(override val register: Boolean, val text: String) : AuthState()

    /** A submit is in flight; it stays busy until the app moves it back to idle. */
    data class Busy(override val register: Boolean) : AuthState()

    /** The face the state was built on: create (register) or sign in (login). */
    abstract val register: Boolean

    /**
     * The shake surface, a fixed 600 ms on the states that refuse or warn so the host can drive
     * the masked-then-shaken look, and null on the states that carry no shake.
     */
    val shakeMs: Long? get() = when (this) {
        is Refused -> 600L
        is Warning -> 600L
        else -> null
    }

    /** A state's own label, which never names a password or carries the typed text. */
    final override fun toString(): String = when (this) {
        is Idle -> "Idle(register=$register)"
        is Busy -> "Busy(register=$register)"
        is Refused -> "Refused"
        is Warning -> "Warning"
    }
}
