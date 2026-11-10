package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.UpdateRoleResult
import dev.breaker.server.syncapi.accounts.UsernameRules
import dev.breaker.server.syncapi.auth.receiveAuthBody
import dev.breaker.server.syncapi.auth.respondBadRequest
import dev.breaker.server.syncapi.escapeJsonString
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncJsonLiteral
import dev.breaker.server.syncapi.transcriptions.SyncJsonText
import dev.breaker.server.syncapi.transcriptions.readSyncJsonObject
import dev.breaker.server.syncapi.transcriptions.respondUnavailable
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException

// The exact 404 body from openapi.yaml: valid for an unknown username and for a name
// that fails the username rules (the route treats both as "no such account").
private const val ACCOUNT_NOT_FOUND_BODY =
    """{"error":"not_found","message":"Account not found"}"""

// The exact 409 body from openapi.yaml: the last admin cannot be demoted.
private const val LAST_ADMIN_BODY =
    """{"error":"conflict","message":"The last admin cannot be demoted"}"""

/** The roles body: `username` a string and `admin` the literal true or false, and nothing else. */
private sealed class RoleBody {
    object Bad : RoleBody()

    class Ok(val username: String, val admin: Boolean) : RoleBody()
}

private fun parseRoleBody(body: String): RoleBody {
    val members = readSyncJsonObject(body) ?: return RoleBody.Bad
    if (members.size != 2) return RoleBody.Bad
    val username = (members["username"] as? SyncJsonText)?.value ?: return RoleBody.Bad
    return when ((members["admin"] as? SyncJsonLiteral)?.text) {
        "true" -> RoleBody.Ok(username, true)
        "false" -> RoleBody.Ok(username, false)
        else -> RoleBody.Bad
    }
}

/**
 * Installs `POST /v1/admin/roles` on this application.
 *
 * Admin-only: grant or revoke another account's admin role. An admin assigns the admin role
 * to a fresh account so it can bootstrap; revoke it to demote a former admin back to user.
 * The route checks, in this order: the caller (401 for any dead, missing or malformed token),
 * the admin role (403), the body size cap (413), the body shape (exactly `username` string and
 * `admin` boolean: 400), the account (404 for unknown or invalid names), then the store. The
 * last admin cannot be demoted: 409. A store failure answers the fixed 503 and no exception
 * text reaches a body, a log or a message. Nothing logs the target username or role (F32).
 */
internal fun Application.adminSetRoleRoute(
    tokenStore: TokenStore,
    accountStore: AccountStore,
) {
    routing {
        post("/v1/admin/roles") {
            val presented = bearerToken(call.request.header("Authorization"))
            val resolved = try {
                tokenStore.resolve(presented)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
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
            when (val parsed = parseRoleBody(body)) {
                RoleBody.Bad -> {
                    call.respondBadRequest()
                    return@post
                }
                is RoleBody.Ok -> {
                    if (!UsernameRules.isValid(parsed.username)) {
                        call.respondText(ACCOUNT_NOT_FOUND_BODY, ContentType.Application.Json, HttpStatusCode.NotFound)
                        return@post
                    }
                    val folded = UsernameRules.fold(parsed.username)
                    val outcome = try {
                        accountStore.setAdminRole(folded, parsed.admin)
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        call.respondUnavailable()
                        return@post
                    }
                    when (outcome) {
                        is UpdateRoleResult.Updated -> {
                            call.respondText(
                                """{"username":${escapeJsonString(outcome.username)},"role":${escapeJsonString(outcome.role.dbValue)}}""",
                                ContentType.Application.Json,
                                HttpStatusCode.OK,
                            )
                        }
                        UpdateRoleResult.NoSuchAccount -> {
                            call.respondText(ACCOUNT_NOT_FOUND_BODY, ContentType.Application.Json, HttpStatusCode.NotFound)
                        }
                        UpdateRoleResult.LastAdmin -> {
                            call.respondText(LAST_ADMIN_BODY, ContentType.Application.Json, HttpStatusCode.Conflict)
                        }
                    }
                }
            }
        }
    }
}
