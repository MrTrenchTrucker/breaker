package dev.breaker.shared.api

/**
 * The request to log in.
 *
 * Mirrors the `LoginRequest` schema in the OpenAPI spec.
 * The raw authVerifier is a login credential in its own right — never logged.
 */
data class LoginRequest(
    val username: String,
    val authVerifier: String,
) {
    init {
        require(username.matches(Regex("^[A-Za-z0-9._-]{1,64}$"))) {
            "username must be 1..64 chars of [A-Za-z0-9._-]"
        }
        require(authVerifier.matches(Regex("^[A-Za-z0-9+/]{43}=$"))) {
            "authVerifier must be base64 that decodes to 32 bytes"
        }
    }
}
