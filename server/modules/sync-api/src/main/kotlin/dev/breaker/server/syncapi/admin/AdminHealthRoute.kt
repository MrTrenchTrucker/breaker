package dev.breaker.server.syncapi.admin

import dev.breaker.server.syncapi.escapeJsonString
import dev.breaker.server.syncapi.tokens.ResolvedToken
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.respondUnavailable
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CancellationException

/** Installs `GET /v1/admin/health` on this application.
 *
 * Admin-only health: answers 200 {"status":"ok","git_tag":"<tag>"} whenever an admin token
 * resolves — the process and the database answer, so status is always "ok". Never 404 or 503
 * for a missing manifest; there is no manifest and no UpdatesRoute involvement here.
 *
 * The bearer token identifies the caller (401 for any dead, missing or malformed token); the
 * caller must hold the admin role (403). A failure of the token check is the fixed 503
 * (respondUnavailable). The git tag comes from config: unset (an empty value) answers "unknown"; nothing in
 * the route touches the file system or the release manifest.
 */
internal fun Application.adminHealthRoute(
    tokenStore: TokenStore,
    gitTag: String,
) {
    routing {
        get("/v1/admin/health") {
            val presented = bearerToken(call.request.header("Authorization"))
            val resolved: ResolvedToken? = try {
                tokenStore.resolve(presented)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Exception) {
                call.respondUnavailable()
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
            val tag = if (gitTag.isEmpty()) "unknown" else gitTag
            call.respondText(
                """{"status":"ok","git_tag":${escapeJsonString(tag)}}""",
                ContentType.Application.Json,
                HttpStatusCode.OK,
            )
        }
    }
}
