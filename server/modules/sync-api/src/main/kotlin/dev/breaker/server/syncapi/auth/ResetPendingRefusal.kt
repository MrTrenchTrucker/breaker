package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.transcriptions.respondSyncError
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall

// The text a person reads when the account has been reset and the new password is not set yet.
private const val MESSAGE_RESET_PENDING = "The account is waiting for a reset to be completed"

/** Answers 409 for an account that is waiting for a reset to be completed. */
internal suspend fun ApplicationCall.respondResetPending() {
    respondSyncError(HttpStatusCode.Conflict, "conflict", MESSAGE_RESET_PENDING)
}
