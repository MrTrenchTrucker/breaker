package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.auth.StoreCall
import dev.breaker.server.syncapi.auth.guardedStoreCall
import dev.breaker.server.syncapi.auth.parseDeviceId
import dev.breaker.server.syncapi.tokens.ResolvedToken
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.delete
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException

/**
 * Installs `DELETE /v1/admin/sessions/{id}` on this application.
 *
 * Admin-only: ends one session by id, of any account. The bearer token identifies the caller;
 * the caller must hold the admin role — a session token through its owner's role, an agent token
 * through its effective scope. Auth is resolved before the id is parsed, so a non-admin sending a
 * garbage id gets 403, not 404. The id must be decimal digits only (no sign, no overflow); a bad
 * id and any id that is not a live session of any account (unknown, an agent token row, already
 * revoked, expired, past the 180-day cap) all answer one fixed 404 carrying no part of the id.
 * An admin may end another account's session or their own current session. Agent tokens are never
 * revoked by this route, and the account's hand-off codes are not deleted when one session ends.
 * A missing or dead token answers 401 and a store failure the fixed 503, each with the module's
 * fixed body and nothing else.
 *
 * Hand-written JSON — no serialization library, no api-contracts import.
 */
internal fun Application.adminEndSessionRoute(tokenStore: TokenStore) {
    routing {
        delete("/v1/admin/sessions/{id}") {
            val token = bearerToken(call.request.header("Authorization"))
            val resolved: ResolvedToken? = try {
                tokenStore.resolve(token)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondText(SERVICE_UNAVAILABLE_BODY, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                return@delete
            }
            if (resolved == null) {
                call.respondText(UNAUTHORIZED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@delete
            }
            if (!isAdmin(resolved)) {
                call.respondText(FORBIDDEN_BODY, ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@delete
            }
            val sessionId = call.parameters["id"]?.let(::parseDeviceId)
            if (sessionId == null) {
                call.respondText(SESSION_NOT_FOUND_BODY, ContentType.Application.Json, HttpStatusCode.NotFound)
                return@delete
            }
            when (val guarded = call.guardedStoreCall {
                tokenStore.revokeSessionById(sessionId)
            }) {
                is StoreCall.Done -> {
                    if (guarded.value) {
                        call.respondText("", ContentType.Application.Json, HttpStatusCode.NoContent)
                    } else {
                        call.respondText(SESSION_NOT_FOUND_BODY, ContentType.Application.Json, HttpStatusCode.NotFound)
                    }
                }
                StoreCall.Failed -> return@delete
            }
        }
    }
}

/** No such session: a bad id, an unknown id, an agent token id, an already revoked or expired
 * session, or an id past the 180-day cap — all answer this same text. No part of the id is echoed
 * into the body. */
internal const val SESSION_NOT_FOUND_BODY =
    """{"error":"not_found","message":"No such session"}"""
