package dev.breaker.server.syncapi.servicecheck

import dev.breaker.server.syncapi.tokens.ResolvedToken
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

// The two internal routes that whisper-server will call. They are not mounted on any
// listener here: where they listen and where the service key is made come in a later
// slice. They are never on the VPN-facing interface.
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

    post("/internal/v1/token/use") {
        if (!key.matches(call.request.headers["X-Service-Key"])) {
            refuse(call, HttpStatusCode.Forbidden, "bad_service_key")
            return@post
        }
        answer(call, store.resolve(call.request.headers["X-Presented-Token"] ?: ""))
    }

    post("/internal/v1/token/peek") {
        if (!key.matches(call.request.headers["X-Service-Key"])) {
            refuse(call, HttpStatusCode.Forbidden, "bad_service_key")
            return@post
        }
        answer(call, store.peek(call.request.headers["X-Presented-Token"] ?: ""))
    }
}
