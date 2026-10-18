package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.ResolvedToken
import dev.breaker.server.syncapi.tokens.TokenKind
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.routing

/**
 * Installs `DELETE /v1/admin/agent-tokens/{id}` on this application.
 *
 * Admin-only: revokes one agent token by id. The bearer token identifies the caller;
 * the caller must hold the admin role — a session token through its owner's role, an
 * agent token through its effective scope. A missing, unknown, expired, revoked or
 * malformed token all answer the same 401 and disclose nothing about which it was.
 * An id that has no agent-token row at all (never existed, a session token, or not
 * a number) is a plain 404. An agent token that is already revoked still answers
 * 204: the revoke is idempotent and the end state is the same.
 *
 * Hand-written JSON — no serialization library, no api-contracts import.
 * The error body is the shared `Error` object with the snake_case wire names
 * `error` and `message`, exactly as openapi.yaml lines 292-320 give them.
 */
internal fun Application.adminRevokeAgentTokenRoute(tokenStore: TokenStore) {
    routing {
        delete("/v1/admin/agent-tokens/{id}") {
            val token = bearerToken(call.request.header("Authorization"))
            val resolved = tokenStore.resolve(token)
            if (resolved == null) {
                call.respondText(UNAUTHORIZED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@delete
            }
            if (!isAdmin(resolved)) {
                call.respondText(FORBIDDEN_BODY, ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@delete
            }
            // Idempotent revoke: an already-revoked agent token still answers 204.
            // Only an id with no agent-token row at all is a 404.
            val tokenId = call.parameters["id"]?.toLongOrNull()
            if (tokenId == null || !tokenStore.agentTokenExists(tokenId)) {
                call.respondText(NOT_FOUND_BODY, ContentType.Application.Json, HttpStatusCode.NotFound)
                return@delete
            }
            tokenStore.revokeAgentToken(tokenId)
            call.respondText("", ContentType.Application.Json, HttpStatusCode.NoContent)
        }
    }
}

/**
 * The owner's role right now decides admin: a session token carries no scope of its
 * own, so its owner's current role is the whole test; an agent token acts only at its
 * effective scope, which is `admin` only while its owner is an admin (ADR-009).
 */
private fun isAdmin(resolved: ResolvedToken): Boolean =
    if (resolved.kind == TokenKind.SESSION) {
        resolved.role == Role.ADMIN
    } else {
        resolved.effectiveScope == AgentScope.ADMIN
    }

/**
 * Reads the bearer credential out of an `Authorization` header by hand.
 *
 * The scheme word is case-insensitive (`bearer`, `Bearer`, `BEARER` all work); the
 * credential after the single separating space is returned exactly as written, so a
 * wrong spelling, an embedded space or an empty credential survives to the store's own
 * shape check and is refused there. Anything else — a different scheme, a missing
 * space, a bare token with no scheme — is no token at all.
 */
private fun bearerToken(header: String?): String {
    if (header == null) return ""
    val separator = header.indexOf(' ')
    if (separator <= 0 || separator == header.length - 1) return ""
    val scheme = header.substring(0, separator)
    if (!scheme.equals("bearer", ignoreCase = true)) return ""
    return header.substring(separator + 1)
}

// The `Error` wire object from openapi.yaml: the exact set of names {error, message},
// no other key. A test asserts this set, so a second field is not added casually.
internal const val UNAUTHORIZED_BODY =
    """{"error":"unauthorized","message":"Invalid or missing token"}"""
internal const val FORBIDDEN_BODY =
    """{"error":"forbidden","message":"Admin role required"}"""
internal const val NOT_FOUND_BODY =
    """{"error":"not_found","message":"Agent token not found"}"""
