package dev.breaker.server.syncapi.servicecheck

import dev.breaker.server.syncapi.escapeJsonString
import dev.breaker.server.syncapi.tokens.ResolvedToken
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import java.util.Base64
import kotlinx.coroutines.CancellationException

// Runs a token-store call and answers it. The service key is checked by the route before this
// is entered, so a wrong key is always 403 first. A store failure other than coroutine
// cancellation (the closed-database IllegalStateException, SQL errors) answers 503 with the
// fixed body; the exception text never reaches a body, a log or a message. Only the store call
// is guarded: a failure while writing the answer is not turned into a second answer.
internal suspend fun serve(
    call: ApplicationCall,
    keysFor: (suspend (Long) -> ByteArray?)? = null,
    block: suspend () -> ResolvedToken?,
) {
    val resolved: ResolvedToken? = try {
        block()
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        refuse(call, HttpStatusCode.ServiceUnavailable, "unavailable")
        return
    }
    answer(call, resolved, keysFor)
}

internal suspend fun answer(
    call: ApplicationCall,
    resolved: ResolvedToken?,
    keysFor: (suspend (Long) -> ByteArray?)? = null,
) {
    if (resolved == null) {
        refuse(call, HttpStatusCode.Unauthorized, "not_live")
        return
    }
    val kindText = escapeJsonString(resolved.kind.dbValue)
    val roleText = escapeJsonString(resolved.role.dbValue)
    call.respondText(
        """{"account_id":${resolved.accountId},"kind":$kindText,"role":$roleText${boxPubkeyMember(resolved.accountId, keysFor)}}""",
        ContentType.Application.Json,
        HttpStatusCode.OK,
    )
}

internal suspend fun refuse(call: ApplicationCall, status: HttpStatusCode, error: String) {
    val errorText = escapeJsonString(error)
    call.respondText("""{"error":$errorText}""", ContentType.Application.Json, status)
}

// The box_pubkey member of the live answer, with its leading comma. With no lookup given the member is left
// out and the body is the old one. With a lookup it is the owner's 32-byte public key as a JSON string of its
// standard padded base64 text (the same text the login answer uses), or null for an account with no key yet.
// A lookup that fails, other than by cancellation (which is passed on), leaves the member out: the token check
// is still answered and nothing of the failure reaches the body.
private suspend fun boxPubkeyMember(accountId: Long, keysFor: (suspend (Long) -> ByteArray?)?): String {
    if (keysFor == null) return ""
    val key: ByteArray? = try {
        keysFor(accountId)
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        return ""
    }
    if (key == null) return ",\"box_pubkey\":null"
    return ",\"box_pubkey\":\"" + Base64.getEncoder().encodeToString(key) + "\""
}

// The two internal routes that whisper-server calls. They are mounted only on the internal
// listener, never on the public listener or the VPN interface. The service key they check
// is made by the server on its first start.
internal fun Route.serviceCheckRoutes(
    store: TokenStore,
    serviceKey: String,
    keysFor: (suspend (Long) -> ByteArray?)? = null,
) {
    val key = ServiceKey(serviceKey)

    post("/internal/v1/token/use") {
        if (!key.matches(call.request.headers["X-Service-Key"])) {
            refuse(call, HttpStatusCode.Forbidden, "bad_service_key")
            return@post
        }
        serve(call, keysFor) { store.resolve(call.request.headers["X-Presented-Token"] ?: "") }
    }

    post("/internal/v1/token/peek") {
        if (!key.matches(call.request.headers["X-Service-Key"])) {
            refuse(call, HttpStatusCode.Forbidden, "bad_service_key")
            return@post
        }
        serve(call, keysFor) { store.peek(call.request.headers["X-Presented-Token"] ?: "") }
    }
}
