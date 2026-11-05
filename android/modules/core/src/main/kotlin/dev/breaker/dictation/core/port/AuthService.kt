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
