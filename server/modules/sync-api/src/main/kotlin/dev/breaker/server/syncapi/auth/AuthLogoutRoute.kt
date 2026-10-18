package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.tokens.TokenKind
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

/**
 * Installs `POST /v1/auth/logout` on this application.
 *
 * The request carries no body: the presented bearer token is the whole request.
 * Logout revokes ONLY the token that was presented — never the account's other
 * sessions (those are `change-password`'s to revoke) and never its agent tokens
 * (those are revoked only through `DELETE /v1/admin/agent-tokens/{id}`). A
 * missing, unknown, expired, already-revoked or non-session token all answer the
 * same 401: nothing beyond "not a valid session" is disclosed.
 *
 * Hand-written JSON — no serialization library, no api-contracts import.
 * Wire names match openapi.yaml exactly: the 401 body is the shared `ApiError`
 * `{"error","message"}`, and the 204 carries no content.
 */
internal fun Application.authLogoutRoute(tokenStore: TokenStore) {
    routing {
        post("/v1/auth/logout") {
            val presented = presentedToken(call.request.headers[HttpHeaders.Authorization])
            if (presented == null) {
                call.respondUnauthorized()
                return@post
            }

            val resolved = tokenStore.resolve(presented)
            // An unknown, expired or revoked token, and an agent token, are all the
            // same answer: the presented token is not a live session.
            if (resolved == null || resolved.kind != TokenKind.SESSION) {
                call.respondUnauthorized()
                return@post
            }

            tokenStore.revokeSession(resolved.tokenId)
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/**
 * Reads the presented token out of an `Authorization` header.
 *
 * Returns null unless the header is `Bearer <token>` with a non-empty token that
 * carries no whitespace. The scheme is case-insensitive (RFC 6750); a missing
 * scheme, an empty token and a token with spaces are all refused. The token is
 * returned exactly as presented — no trimming — so an altered spelling simply
 * fails to resolve further down.
 */
private fun presentedToken(header: String?): String? {
    if (header == null) return null
    val separator = header.indexOf(' ')
    if (separator <= 0) return null
    if (!header.substring(0, separator).equals(BEARER_SCHEME, ignoreCase = true)) return null
    val token = header.substring(separator + 1)
    if (token.isEmpty()) return null
    if (token.any { character -> character.isWhitespace() }) return null
    return token
}

/**
 * The single 401 body. Fixed, so the presented token, the account and the reason
 * never appear in a response.
 */
private suspend fun ApplicationCall.respondUnauthorized() {
    respondText(
        """{"error":"unauthorized","message":"Invalid or missing token"}""",
        ContentType.Application.Json,
        HttpStatusCode.Unauthorized,
    )
}

private const val BEARER_SCHEME = "Bearer"
