package dev.breaker.dictation.core.model

/** What a user is allowed to do. Decided by the server, never by the phone. */
enum class UserRole { ADMIN, USER }

/** What an API token may call. */
enum class TokenScope { TRANSCRIBE, ADMIN }

/**
 * A signed-in user.
 *
 * [token] is the bearer token the server issued; the platform keeps it in its
 * keystore, and the domain only passes it along. [role] and [scopes] are a copy
 * of what the server said — the phone uses them to decide what to *show*, and
 * the server still checks every request, so a tampered phone gains nothing.
 */
data class AuthSession(
    val userId: String,
    val username: String,
    val token: String,
    val role: UserRole,
    val scopes: Set<TokenScope>,
) {
    init {
        require(userId.isNotBlank()) { "A session needs a non-blank user id" }
        require(username.isNotBlank()) { "A session needs a non-blank username" }
        require(token.isNotBlank()) { "A session needs a non-blank token" }
    }

    val isAdmin: Boolean get() = role == UserRole.ADMIN

    /** Never prints the token. */
    override fun toString(): String =
        "AuthSession(userId=$userId, username=$username, role=$role, scopes=$scopes)"
}
