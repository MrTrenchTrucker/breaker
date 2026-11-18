package dev.breaker.dictation.core.port

import dev.breaker.dictation.core.model.AuthSession

/**
 * Signs users in and keeps their token.
 *
 * Implemented by the auth module, which stores the token in the platform
 * keystore. The first account to register is an admin; the server decides that,
 * not the phone.
 */
interface AuthService {
    /**
     * Register a new account and sign in. The first account registered is an admin.
     * The service CLEARS the array it is given before it returns, on success and on
     * failure; the caller must not reuse it.
     */
    fun register(username: String, password: CharArray): AuthSession

    /**
     * Register a new account that was invited with a one-time install code, and sign in.
     *
     * The server's first registration carries a one-time install code. This overload is
     * the port's way of accepting one without disturbing any existing implementer or
     * caller: an implementation that has not opted in keeps compiling, and the default
     * body decides what a code means.
     *
     * A null [installCode] is the ordinary path and delegates to [register] with the
     * SAME arguments — the same [password] array reference included, never a copy,
     * because the service clears the array it is given before it returns. A non-null
     * code is refused loudly, so a code the user typed is never silently dropped.
     *
     * [installCode] is a credential: it is never printed, never written to a log and
     * never carried in a message or an exception text.
     */
    fun register(username: String, password: CharArray, installCode: String?): AuthSession {
        if (installCode == null) return register(username, password)
        throw UnsupportedOperationException(
            "Registration with an install code is not supported by this AuthService implementation."
        )
    }

    /**
     * Sign in. Throws when the credentials are refused.
     * The service CLEARS the array it is given before it returns, on success and on
     * failure; the caller must not reuse it.
     */
    fun login(username: String, password: CharArray): AuthSession

    /** Sign out and destroy the stored token. */
    fun logout()

    /** The current session, or null when signed out. */
    fun currentSession(): AuthSession?
}
