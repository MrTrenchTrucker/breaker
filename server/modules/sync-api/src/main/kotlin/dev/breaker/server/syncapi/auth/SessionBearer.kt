package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.tokens.ResolvedToken
import dev.breaker.server.syncapi.tokens.TokenKind
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

/** The outcome of checking the bearer credential of a route that needs a user session. */
internal sealed class SessionCheck {
    /** A live session token; [token] carries the account the route scopes its work to. */
    class Live(val token: ResolvedToken) : SessionCheck()

    /** No credential, a malformed one, or one that is unknown, expired or revoked. */
    object Unauthorized : SessionCheck()

    /** A live agent token, which a session-only route refuses. */
    object Forbidden : SessionCheck()
}

/**
 * Checks the `Authorization` header of a route that needs a live user session token.
 *
 * The header is read with the same rules as the logout route: the scheme is case-insensitive,
 * the credential follows one space, and it is non-empty and holds no whitespace. An unknown,
 * expired or revoked token is [SessionCheck.Unauthorized]; a live agent token is
 * [SessionCheck.Forbidden]. The presented text is never kept in a result.
 */
internal suspend fun checkSessionBearer(header: String?, tokenStore: TokenStore): SessionCheck {
    val presented = bearerCredential(header) ?: return SessionCheck.Unauthorized
    val resolved = tokenStore.resolve(presented) ?: return SessionCheck.Unauthorized
    if (resolved.kind == TokenKind.AGENT) return SessionCheck.Forbidden
    return SessionCheck.Live(resolved)
}

/** Answers a refusal from [checkSessionBearer] with its fixed body. */
internal suspend fun ApplicationCall.respondSessionRefusal(refusal: SessionCheck) {
    if (refusal == SessionCheck.Forbidden) {
        respondText(FORBIDDEN_AGENT_BODY, ContentType.Application.Json, HttpStatusCode.Forbidden)
    } else {
        respondText(UNAUTHORIZED_SESSION_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
    }
}

private fun bearerCredential(header: String?): String? {
    if (header == null) return null
    val separator = header.indexOf(' ')
    if (separator <= 0) return null
    if (!header.substring(0, separator).equals(BEARER_SCHEME, ignoreCase = true)) return null
    val token = header.substring(separator + 1)
    if (token.isEmpty()) return null
    if (token.any { character -> character.isWhitespace() }) return null
    return token
}

private const val BEARER_SCHEME = "Bearer"

internal const val UNAUTHORIZED_SESSION_BODY =
    """{"error":"unauthorized","message":"Invalid or missing user session token"}"""

internal const val FORBIDDEN_AGENT_BODY =
    """{"error":"forbidden","message":"User session token required; an agent token is refused"}"""
