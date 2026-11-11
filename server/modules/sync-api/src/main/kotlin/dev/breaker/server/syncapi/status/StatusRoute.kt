package dev.breaker.server.syncapi.status

import dev.breaker.server.syncapi.admin.SERVICE_UNAVAILABLE_BODY
import dev.breaker.server.syncapi.admin.UNAUTHORIZED_BODY
import dev.breaker.server.syncapi.admin.bearerToken
import dev.breaker.server.syncapi.tokens.ResolvedToken
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.call
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.coroutines.CancellationException

/**
 * `GET /v1/status`: which services are up, names and booleans only, for any signed-in user (a
 * session or an agent token; no role check and no scope check). sync-api is up because it answered.
 * [whisper] is null when no whisper-server health URL is configured: then whisper-server is not
 * listed at all, so a stack without it never shows "down". A failing probe is "up": false, never a 500.
 */
internal fun Route.statusRoute(tokenStore: TokenStore, whisper: StatusProbe?) {
    get("/v1/status") {
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
        call.respondText(statusBody(whisper?.isUp()), ContentType.Application.Json, HttpStatusCode.OK)
    }
}

/** The answer body: sync-api always, whisper-server only when it is configured ([whisperUp] not null). */
internal fun statusBody(whisperUp: Boolean?): String {
    val whisper = if (whisperUp == null) "" else ",{\"name\":\"whisper-server\",\"up\":$whisperUp}"
    return """{"services":[{"name":"sync-api","up":true}$whisper]}"""
}
