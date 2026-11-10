package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.escapeJsonString
import dev.breaker.server.syncapi.tokens.AgentTokenRow
import dev.breaker.server.syncapi.tokens.ResolvedToken
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.formatInstantMs
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException

/**
 * Installs `GET /v1/admin/agent-tokens` on this application.
 *
 * Admin-only: lists every live agent token of every account, oldest first (id ascending).
 * Revoked rows (revoked_at_ms set) are kept in the table but never appear here. Each entry is
 * id, name (label), owner_username, scope and expires_at — never the token value or its hash.
 *
 * Order: bearer token (401 when missing or dead), admin role (403), then the store. A store
 * failure answers the fixed 503 and the exception text never reaches a body, a log or a
 * message.
 *
 * Hand-written JSON — no serialization library, no api-contracts import.
 */
internal fun Application.adminListAgentTokensRoute(tokenStore: TokenStore) {
    routing {
        get("/v1/admin/agent-tokens") {
            val token = bearerToken(call.request.header("Authorization"))
            val resolved: ResolvedToken? = try {
                tokenStore.resolve(token)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondText(SERVICE_UNAVAILABLE_BODY, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                return@get
            }
            if (resolved == null) {
                call.respondText(UNAUTHORIZED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@get
            }
            if (!isAdmin(resolved)) {
                call.respondText(FORBIDDEN_BODY, ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@get
            }
            val rows = try {
                tokenStore.listAgentTokens()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondText(SERVICE_UNAVAILABLE_BODY, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                return@get
            }
            val body = rows.joinToString(
                separator = ",",
                prefix = "[",
                postfix = "]",
            ) { row ->
                val expires = if (row.expiresAtMs == null) {
                    "null"
                } else {
                    escapeJsonString(formatInstantMs(row.expiresAtMs))
                }
                """{"id":${row.id},"name":${escapeJsonString(row.label)},"owner_username":${escapeJsonString(row.ownerUsername)},"scope":${escapeJsonString(row.scope.dbValue)},"expires_at":$expires}"""
            }
            call.respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
        }
    }
}
