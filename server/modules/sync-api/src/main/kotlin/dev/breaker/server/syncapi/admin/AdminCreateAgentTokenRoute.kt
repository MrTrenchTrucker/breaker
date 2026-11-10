package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.UsernameRules
import dev.breaker.server.syncapi.auth.receiveAuthBody
import dev.breaker.server.syncapi.auth.parseObjectFields
import dev.breaker.server.syncapi.auth.respondBadRequest
import dev.breaker.server.syncapi.auth.respondResetPending
import dev.breaker.server.syncapi.escapeJsonString
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.IssueResult
import dev.breaker.server.syncapi.tokens.ResolvedToken
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.formatInstantMs
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.time.Instant
import kotlinx.coroutines.CancellationException

// The exact 404 body from openapi.yaml for an account that does not exist.
private const val ACCOUNT_NOT_FOUND_BODY =
    """{"error":"not_found","message":"Account not found"}"""

/**
 * Installs `POST /v1/admin/agent-tokens` on this application.
 *
 * Admin-only: mints one agent token for the owner account named in the body. The bearer token
 * identifies the caller; `owner_username` is the OWNER, not the caller, and there is no
 * caller==owner rule. `issueAgent` decides everything it decides (label validity, expiry not in
 * the past, the owner role, owner pending-reset, admin scope only for an admin owner); this
 * route only parses the body and maps the outcome.
 *
 * Order: bearer token (401 when missing or dead), admin role (403), body size cap (413), body
 * shape (400), the owner account (404 when unknown), then the store. A store failure answers
 * the fixed 503 and the exception text never reaches a body, a log or a message.
 *
 * Hand-written JSON — no serialization library, no api-contracts import. The create answer
 * returns the token value exactly once; list never returns it.
 */
internal fun Application.adminCreateAgentTokenRoute(tokenStore: TokenStore) {
    routing {
        post("/v1/admin/agent-tokens") {
            val token = bearerToken(call.request.header("Authorization"))
            val resolved: ResolvedToken? = try {
                tokenStore.resolve(token)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondText(SERVICE_UNAVAILABLE_BODY, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                return@post
            }
            if (resolved == null) {
                call.respondText(UNAUTHORIZED_BODY, ContentType.Application.Json, HttpStatusCode.Unauthorized)
                return@post
            }
            if (!isAdmin(resolved)) {
                call.respondText(FORBIDDEN_BODY, ContentType.Application.Json, HttpStatusCode.Forbidden)
                return@post
            }
            val body = call.receiveAuthBody() ?: return@post
            val fields = parseObjectFields(body, required = listOf("name", "owner_username"), optional = listOf("scope", "expires_at")) ?: run {
                call.respondBadRequest()
                return@post
            }
            val name = fields["name"]
            val ownerUsername = fields["owner_username"]
            if (name == null || ownerUsername == null) {
                call.respondBadRequest()
                return@post
            }
            val scope = when (val scopeText = fields["scope"]) {
                null -> AgentScope.TRANSCRIBE
                else -> parseAgentScope(scopeText) ?: run {
                    call.respondBadRequest()
                    return@post
                }
            }
            val expiresAt = when (val expiresText = fields["expires_at"]) {
                null -> null
                else -> try {
                    Instant.parse(expiresText)
                } catch (_: Exception) {
                    call.respondBadRequest()
                    return@post
                }
            }
            val ownerId = try {
                tokenStore.accountIdOfFolded(UsernameRules.fold(ownerUsername))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondText(SERVICE_UNAVAILABLE_BODY, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                return@post
            }
            if (ownerId == null) {
                call.respondText(ACCOUNT_NOT_FOUND_BODY, ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }
            val issued = try {
                tokenStore.issueAgent(ownerId, scope, name, expiresAt)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondText(SERVICE_UNAVAILABLE_BODY, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                return@post
            }
            when (issued) {
                is IssueResult.Issued -> {
                    val ownerName = try {
                        tokenStore.usernameOf(ownerId)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        call.respondText(SERVICE_UNAVAILABLE_BODY, ContentType.Application.Json, HttpStatusCode.ServiceUnavailable)
                        return@post
                    }
                    val expiresText = if (issued.token.expiresAt == null) {
                        "null"
                    } else {
                        escapeJsonString(formatInstantMs(issued.token.expiresAt.toEpochMilli()))
                    }
                    call.respondText(
                        """{"token":${escapeJsonString(issued.token.secret.reveal())},"name":${escapeJsonString(name)},"owner_username":${escapeJsonString(ownerName ?: "")},"scope":${escapeJsonString(scope.dbValue)},"expires_at":$expiresText}""",
                        ContentType.Application.Json,
                        HttpStatusCode.Created,
                    )
                }
                IssueResult.NoSuchAccount -> {
                    call.respondText(ACCOUNT_NOT_FOUND_BODY, ContentType.Application.Json, HttpStatusCode.NotFound)
                }
                IssueResult.InvalidLabel, IssueResult.InvalidExpiry, IssueResult.ScopeNotAllowed -> {
                    call.respondBadRequest()
                }
                IssueResult.ResetPending -> {
                    call.respondResetPending()
                }
            }
        }
    }
}

// Reads the request scope the same way the wire type does: case-insensitive against
// `transcribe` and `admin`, anything else refused. Kept in this module — the route does not
// import api-contracts types.
private fun parseAgentScope(value: String): AgentScope? =
    when (value.lowercase()) {
        "transcribe" -> AgentScope.TRANSCRIBE
        "admin" -> AgentScope.ADMIN
        else -> null
    }
