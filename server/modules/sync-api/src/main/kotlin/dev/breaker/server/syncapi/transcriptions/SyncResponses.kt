package dev.breaker.server.syncapi.transcriptions

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText

// The fixed error bodies of the sync routes. Each body is the shared error object with exactly the
// wire names "error" and "message". No part of the request is ever echoed into a body.

internal const val MESSAGE_SESSION_REQUIRED: String = "Invalid or missing user session token"
internal const val MESSAGE_AGENT_REFUSED: String = "User session token required; an agent token is refused"
internal const val MESSAGE_UNAVAILABLE: String = "The service is unavailable, try again later"
internal const val MESSAGE_BODY_TOO_LARGE: String = "Request body exceeds maximum allowed size"
internal const val MESSAGE_ABOVE_ACCOUNT: String = "key_version is above the account's current key_version"
// The text of the example of the 409 response in the openapi.yaml of the shared API (lines 437-445).
internal const val MESSAGE_LOWER_REFUSED: String = "A lower key_version is refused for this client_id"

internal suspend fun ApplicationCall.respondSyncError(status: HttpStatusCode, error: String, message: String) {
    respondText("""{"error":"$error","message":"$message"}""", ContentType.Application.Json, status)
}

internal suspend fun ApplicationCall.respondSessionRequired() {
    respondSyncError(HttpStatusCode.Unauthorized, "unauthorized", MESSAGE_SESSION_REQUIRED)
}

internal suspend fun ApplicationCall.respondAgentRefused() {
    respondSyncError(HttpStatusCode.Forbidden, "forbidden", MESSAGE_AGENT_REFUSED)
}

internal suspend fun ApplicationCall.respondUnavailable() {
    respondSyncError(HttpStatusCode.ServiceUnavailable, "unavailable", MESSAGE_UNAVAILABLE)
}

internal suspend fun ApplicationCall.respondBodyTooLarge() {
    respondSyncError(HttpStatusCode.PayloadTooLarge, "payload_too_large", MESSAGE_BODY_TOO_LARGE)
}

internal suspend fun ApplicationCall.respondBadRequest(message: String) {
    respondSyncError(HttpStatusCode.BadRequest, "bad_request", message)
}

internal suspend fun ApplicationCall.respondAboveAccount() {
    respondSyncError(HttpStatusCode.Conflict, "conflict", MESSAGE_ABOVE_ACCOUNT)
}

internal suspend fun ApplicationCall.respondLowerRefused() {
    respondSyncError(HttpStatusCode.Conflict, "conflict", MESSAGE_LOWER_REFUSED)
}
