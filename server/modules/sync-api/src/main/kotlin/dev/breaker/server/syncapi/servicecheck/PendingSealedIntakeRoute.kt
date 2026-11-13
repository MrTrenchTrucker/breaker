package dev.breaker.server.syncapi.servicecheck

import dev.breaker.server.syncapi.db.MAX_SEALED_BYTES
import dev.breaker.server.syncapi.db.PendingInsertResult
import dev.breaker.server.syncapi.transcriptions.SYNC_BODY_CAP_BYTES
import dev.breaker.server.syncapi.transcriptions.SyncJsonNumeral
import dev.breaker.server.syncapi.transcriptions.SyncJsonText
import dev.breaker.server.syncapi.transcriptions.SyncJsonValue
import dev.breaker.server.syncapi.transcriptions.formatInstantMs
import dev.breaker.server.syncapi.transcriptions.isCanonicalUuid
import dev.breaker.server.syncapi.transcriptions.readSyncJsonObject
import dev.breaker.server.syncapi.transcriptions.respondBodyTooLarge
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.contentLength
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import io.ktor.utils.io.readBuffer
import java.util.Base64
import kotlinx.coroutines.CancellationException
import kotlinx.io.readByteArray

// POST /internal/v1/pending-sealed: whisper-server hands a sealed copy of a job result over (it does not call
// this yet). The body is exactly {"account_id": <positive whole number>, "job_id": "<lowercase canonical UUID>",
// "sealed_b64": "<standard padded base64>"}; the job id becomes the row id and later the owner's client_id.
// Answers, in this order:
//   wrong or missing X-Service-Key            403 bad_service_key, nothing read, nothing stored
//   body over SYNC_BODY_CAP_BYTES             413 payload_too_large, by declared length or by the bytes read
//   body of another shape, or a bad value     400 bad_request
//   sealed bytes over MAX_SEALED_BYTES        413 payload_too_large, nothing stored, [onOversize] is called
//   no such account                           404 no_such_account
//   the job id is already a row, any account  200 {"stored":false}, nothing changed
//   the account holds PENDING_PER_ACCOUNT_MAX 409 pending_full, nothing stored
//   otherwise                                 201 {"stored":true}
// sealed_b64 must be the canonical padded base64 of at least 49 bytes (a crypto_box_seal output is 48 bytes
// longer than its content). It is decoded and encoded again, and a text that does not come back the same is 400:
// the owner's client refuses such a text, so the row would hold one of the account's slots for good. The row
// keeps the text as sent. created_at is formatInstantMs of [nowMs], the system clock unless a test gives one.
// A store failure other than cancellation is the fixed 503 unavailable; cancellation is passed on.
// [insert] is the store's insert. [onOversize] gets the account id and the decoded size and nothing else, so
// no content reaches a log.
internal fun Route.pendingSealedIntakeRoute(
    serviceKey: String,
    insert: suspend (accountId: Long, id: String, sealedBox: String, createdAt: String) -> PendingInsertResult,
    onOversize: (accountId: Long, size: Int) -> Unit = { _, _ -> },
    nowMs: () -> Long = { System.currentTimeMillis() },
) {
    val key = ServiceKey(serviceKey)

    post("/internal/v1/pending-sealed") {
        servePendingSealedIntake(call, key, insert, onOversize, nowMs)
    }
}

// The whole answer of the route. It is a top-level function so a test can call it through a test-only route
// and see what a cancelled insert does: it is passed on, never turned into an answer.
internal suspend fun servePendingSealedIntake(
    call: ApplicationCall,
    key: ServiceKey,
    insert: suspend (accountId: Long, id: String, sealedBox: String, createdAt: String) -> PendingInsertResult,
    onOversize: (accountId: Long, size: Int) -> Unit,
    nowMs: () -> Long,
) {
    if (!key.matches(call.request.headers["X-Service-Key"])) {
        refuse(call, HttpStatusCode.Forbidden, "bad_service_key")
        return
    }

    val declared = call.request.contentLength()
    if (declared != null && declared > SYNC_BODY_CAP_BYTES) {
        call.respondBodyTooLarge()
        return
    }
    val bytes = call.receiveChannel().readBuffer(SYNC_BODY_CAP_BYTES + 1).readByteArray()
    if (bytes.size > SYNC_BODY_CAP_BYTES) {
        call.respondBodyTooLarge()
        return
    }

    val members = readSyncJsonObject(String(bytes, Charsets.UTF_8))
    val accountId = members?.takeIf { it.keys == EXPECTED_MEMBERS }?.let { positiveWholeNumber(it["account_id"]) }
    val jobId = (members?.get("job_id") as? SyncJsonText)?.value?.takeIf { isCanonicalUuid(it) }
    val sealedText = (members?.get("sealed_b64") as? SyncJsonText)?.value
    val sealed = sealedText?.let { canonicalBase64(it) }
    if (accountId == null || jobId == null || sealedText == null || sealed == null ||
        sealed.size < MIN_SEALED_BYTES
    ) {
        refuse(call, HttpStatusCode.BadRequest, "bad_request")
        return
    }
    if (sealed.size > MAX_SEALED_BYTES) {
        onOversize(accountId, sealed.size)
        call.respondBodyTooLarge()
        return
    }

    val outcome = try {
        insert(accountId, jobId, sealedText, formatInstantMs(nowMs()))
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (_: Exception) {
        refuse(call, HttpStatusCode.ServiceUnavailable, "unavailable")
        return
    }
    when (outcome) {
        PendingInsertResult.Stored ->
            call.respondText("""{"stored":true}""", ContentType.Application.Json, HttpStatusCode.Created)
        PendingInsertResult.Duplicate ->
            call.respondText("""{"stored":false}""", ContentType.Application.Json, HttpStatusCode.OK)
        PendingInsertResult.PendingFull -> refuse(call, HttpStatusCode.Conflict, "pending_full")
        PendingInsertResult.NoSuchAccount -> refuse(call, HttpStatusCode.NotFound, "no_such_account")
    }
}

// A crypto_box_seal output is the content plus 48 bytes (32 ephemeral key + 16 tag), so 49 is the smallest.
private const val MIN_SEALED_BYTES: Int = 49

private val EXPECTED_MEMBERS: Set<String> = setOf("account_id", "job_id", "sealed_b64")

// Plain digits, no leading zero, at least 1, and a Long; anything else (a sign, a fraction, an exponent) is null.
private fun positiveWholeNumber(value: SyncJsonValue?): Long? {
    val raw = (value as? SyncJsonNumeral)?.raw ?: return null
    if (raw.isEmpty() || raw.length > 18 || !raw.all { it in '0'..'9' } || raw[0] == '0') return null
    return raw.toLong()
}

// The decoded bytes when [text] is exactly the standard padded base64 of them, otherwise null.
private fun canonicalBase64(text: String): ByteArray? {
    val decoded = try {
        Base64.getDecoder().decode(text)
    } catch (_: IllegalArgumentException) {
        return null
    }
    return decoded.takeIf { Base64.getEncoder().encodeToString(it) == text }
}
