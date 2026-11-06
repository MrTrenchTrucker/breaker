package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.UsernameRules
import dev.breaker.server.syncapi.auth.parseObjectFields
import dev.breaker.server.syncapi.auth.receiveAuthBody
import dev.breaker.server.syncapi.auth.respondBadRequest
import dev.breaker.server.syncapi.reset.ResetAccountResult
import dev.breaker.server.syncapi.reset.ResetStore
import dev.breaker.server.syncapi.tokens.TokenStore
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

// The exact 404 body from openapi.yaml for an account that does not exist.
private const val ACCOUNT_NOT_FOUND_BODY =
    """{"error":"not_found","message":"Account not found"}"""

/**
 * Installs `POST /v1/admin/reset-account` on this application.
 *
 * An admin (a session, or an agent token with the admin scope) resets one account: the account loses
 * its keys, its password check, its sessions, agent tokens and stored transcriptions, and gets one
 * new reset code, which is returned once in this response and nowhere else. The route checks, in
 * this order: the caller (401 for any dead, missing or malformed token), the admin role (403), the
 * body size cap (413), the body shape (exactly one member `username`, a string: 400), the name
 * rules and the account (404 for both), then the reset itself. A store failure answers the fixed 503.
 * An admin who resets their own account revokes their own token. Nothing logs the code.
 */
internal fun Application.adminResetAccountRoute(tokenStore: TokenStore, accountStore: AccountStore) {
    val store = ResetStore(accountStore, tokenStore)
    routing {
        post("/v1/admin/reset-account") {
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
            val username = parseObjectFields(body, required = listOf("username"))?.get("username")
            if (username == null) {
                call.respondBadRequest()
                return@post
            }
            if (!UsernameRules.isValid(username)) {
                call.respondText(ACCOUNT_NOT_FOUND_BODY, ContentType.Application.Json, HttpStatusCode.NotFound)
                return@post
            }

            val outcome = try {
                store.resetAccount(UsernameRules.fold(username))
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
                return@post
            }
            when (outcome) {
                is ResetAccountResult.Reset -> {
                    call.respondText(
                        """{"reset_code":"${outcome.code}"}""",
                        ContentType.Application.Json,
                        HttpStatusCode.OK,
                    )
                }
                ResetAccountResult.NoSuchAccount -> {
                    call.respondText(ACCOUNT_NOT_FOUND_BODY, ContentType.Application.Json, HttpStatusCode.NotFound)
                }
            }
        }
    }
}
