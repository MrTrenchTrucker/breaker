package dev.breaker.server.syncapi.servicecheck

import dev.breaker.server.syncapi.tokens.ResolvedToken
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import kotlinx.coroutines.CancellationException

// The two internal routes that whisper-server calls. They are mounted only on the internal
// listener, never on the public listener or the VPN interface. The service key they check
// is made by the server on its first start.
internal fun Route.serviceCheckRoutes(store: TokenStore, serviceKey: String) {
    val key = ServiceKey(serviceKey)

    suspend fun refuse(call: ApplicationCall, status: HttpStatusCode, error: String) {
        call.respondText("""{"error":"$error"}""", ContentType.Application.Json, status)
    }

    suspend fun answer(call: ApplicationCall, resolved: ResolvedToken?) {
        if (resolved == null) {
            refuse(call, HttpStatusCode.Unauthorized, "not_live")
            return
        }
        call.respondText(
            """{"account_id":${resolved.accountId},"kind":"${resolved.kind.dbValue}","role":"${resolved.role.dbValue}"}""",
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }

    // Runs a token-store call and answers it. The service key is checked by the route before this
    // is entered, so a wrong key is always 403 first. A store failure other than coroutine
    // cancellation (the closed-database IllegalStateException, SQL errors) answers 503 with the
    // fixed body; the exception text never reaches a body, a log or a message. Only the store call
    // is guarded: a failure while writing the answer is not turned into a second answer.
    suspend fun serve(call: ApplicationCall, block: suspend () -> ResolvedToken?) {
        val resolved: ResolvedToken? = try {
            block()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Exception) {
            refuse(call, HttpStatusCode.ServiceUnavailable, "unavailable")
            return
        }
        answer(call, resolved)
    }

    post("/internal/v1/token/use") {
        if (!key.matches(call.request.headers["X-Service-Key"])) {
            refuse(call, HttpStatusCode.Forbidden, "bad_service_key")
            return@post
        }
        serve(call) { store.resolve(call.request.headers["X-Presented-Token"] ?: "") }
    }

    post("/internal/v1/token/peek") {
        if (!key.matches(call.request.headers["X-Service-Key"])) {
            refuse(call, HttpStatusCode.Forbidden, "bad_service_key")
            return@post
        }
        serve(call) { store.peek(call.request.headers["X-Presented-Token"] ?: "") }
    }
}
