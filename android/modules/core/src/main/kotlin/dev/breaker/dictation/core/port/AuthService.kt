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
    /** Register a new account and sign in. The first account registered is an admin. */
    fun register(username: String, password: String): AuthSession

    /** Sign in. Throws when the credentials are refused. */
    fun login(username: String, password: String): AuthSession

    /** Sign out and destroy the stored token. */
    fun logout()

    /** The current session, or null when signed out. */
    fun currentSession(): AuthSession?
}
