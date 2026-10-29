package dev.breaker.server.syncapi.sync

import dev.breaker.server.syncapi.auth.SessionCheck
import dev.breaker.server.syncapi.auth.checkSessionBearer
import dev.breaker.server.syncapi.auth.respondSessionRefusal
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.KeyVersions
import dev.breaker.server.syncapi.transcriptions.PushResult
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncBodyException
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post

/**
 * Installs `POST /v1/sync`: one transcription pushed by a user session.
 *
 * Checks run in this order: the session (before the body is read), the declared length, the
 * bounded read, the strict body reader, the client id, the strict base64 check of the encrypted
 * text (for every key version), the decoded size, then, for key version zero, the codec check.
 * The store runs last and refuses a key version above the current one. The decoded value of the
 * codec check is discarded at once.
 */
internal fun Route.syncPushRoute(tokenStore: TokenStore, transcriptionStore: TranscriptionStore) {
    post("/v1/sync") {
        val session = checkSessionBearer(call.request.headers[HttpHeaders.Authorization], tokenStore)
        if (session !is SessionCheck.Live) {
            call.respondSessionRefusal(session)
            return@post
        }
        val declared = call.request.headers[HttpHeaders.ContentLength]?.toLongOrNull()
        if (declared != null && declared > MAX_RAW_BYTES) {
            call.respondTooLarge()
            return@post
        }
        val raw = readBoundedBody(call)
        if (raw == null) {
            call.respondTooLarge()
            return@post
        }
        val request = parsePushRequest(raw)
        if (request == null) {
            call.respondBadRequest()
            return@post
        }
        if (!SyncBodyCodec.isClientId(request.clientId)) {
            call.respondBadRequest()
            return@post
        }
        if (!isStrictBase64(request.encrypted)) {
            call.respondBadRequest()
            return@post
        }
        if (decodedByteCount(request.encrypted) > MAX_DECODED_BYTES) {
            call.respondTooLarge()
            return@post
        }
        if (request.keyVersion <= KeyVersions.CURRENT) {
            try {
                SyncBodyCodec.decodePlain(request.encrypted)
            } catch (refusal: SyncBodyException) {
                call.respondBadRequest()
                return@post
            }
        }
        val result = transcriptionStore.push(session.token.accountId, request.clientId, request.keyVersion, request.encrypted)
        when (result) {
            PushResult.Stored, PushResult.AlreadyHeld -> call.respond(HttpStatusCode.NoContent)
            PushResult.VersionRefused -> call.respondConflict()
        }
    }
}

private suspend fun ApplicationCall.respondBadRequest() =
    respondText(PUSH_BAD_REQUEST_BODY, ContentType.Application.Json, HttpStatusCode.BadRequest)

private suspend fun ApplicationCall.respondTooLarge() =
    respondText(PUSH_PAYLOAD_TOO_LARGE_BODY, ContentType.Application.Json, HttpStatusCode.PayloadTooLarge)

private suspend fun ApplicationCall.respondConflict() =
    respondText(PUSH_CONFLICT_BODY, ContentType.Application.Json, HttpStatusCode.Conflict)

private const val PUSH_BAD_REQUEST_BODY =
    """{"error":"bad_request","message":"The sync push body is not a valid transcription push."}"""

private const val PUSH_PAYLOAD_TOO_LARGE_BODY =
    """{"error":"payload_too_large","message":"The sync push is larger than the allowed size."}"""

private const val PUSH_CONFLICT_BODY =
    """{"error":"conflict","message":"A key_version above the account's current key_version is refused"}"""
