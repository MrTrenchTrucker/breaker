package dev.breaker.server.whisper.http

import dev.breaker.server.whisper.jobs.JobPoll
import dev.breaker.server.whisper.jobs.JobStore
import dev.breaker.server.whisper.json.escapeJsonString
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.MultiPartData
import io.ktor.http.content.PartData
import io.ktor.http.content.forEachPart
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.utils.io.readBuffer
import kotlinx.io.readByteArray

/**
 * The account the fake test token resolves to by default (the test harness lives in the test
 * compilation, which sees this module's internal symbols). Kept in main so the harness can name
 * a concrete owner account; the routes themselves never use a constant - the owner is whatever
 * the presented token resolves to.
 */
internal const val OWNER_ACCOUNT_ID: Long = 1L

/** The single 404 reason for a GET the caller cannot satisfy: an unknown id, a non-numeric id, or a
 *  done job whose result has passed its TTL. One string, so the caller always sees the same reason. */
private const val UNKNOWN_OR_EXPIRED = "unknown or expired"


/** The upload cap, in bytes. Source: the OpenAI audio-transcriptions API limit - this front is deliberately OpenAI-shaped (module card). */
internal const val MAX_AUDIO_BYTES: Long = 25L * 1024 * 1024

internal fun Route.transcriptionRoutes(
    store: JobStore,
    wakeWorker: () -> Unit,
    readMultipart: suspend ApplicationCall.() -> MultiPartData = { receiveMultipart(formFieldLimit = MAX_AUDIO_BYTES + 1) },
    forwardingTo: String = "",
    uptime: () -> String = { "0h0m0s" },
    tokenCheck: TokenCheck = FailClosedTokenCheck,
) {
    post("/v1/audio/transcriptions") {
        // Auth first, before the upload is read: a refused token costs nothing but the header.
        // The enqueue moves a live session token's expiry (use); a refused token is one plain 401.
        val accountId = resolveAccount(call, tokenCheck::use)
        if (accountId == null) {
            respondError(call, HttpStatusCode.Unauthorized, "unauthorized")
            return@post
        }
        var readAudio: ByteArray? = null
        var tooLarge = false

        try {
            val multipart = call.readMultipart()
            multipart.forEachPart { part ->
                when {
                    part is PartData.FileItem && part.name == "file" -> {
                        val bytes = part.provider().readBuffer(MAX_AUDIO_BYTES + 1).readByteArray()
                        if (bytes.size > MAX_AUDIO_BYTES) tooLarge = true else readAudio = bytes
                    }
                    part is PartData.FormItem && part.name == "model" -> {
                        // accept and ignore: the configured downstream model serves every job
                    }
                    part is PartData.FormItem && part.name == "language" -> {
                        // accept and ignore (not stored yet)
                    }
                }
                part.release()
            }
        } catch (e: java.io.IOException) {
            // An IOException while reading the upload is NOT a size overflow.
            // Only a real size overflow (bytes.size > MAX_AUDIO_BYTES) is 413.
            respondError(call, HttpStatusCode.InternalServerError, "the upload could not be read")
            return@post
        }

        // Read the collected fields once: the lambda above writes these vars, so they cannot be
        // smart-cast to non-null afterwards; the vals below can.
        val audio = readAudio

        when {
            tooLarge -> {
                respondError(call, HttpStatusCode.PayloadTooLarge, "the audio is larger than the limit")
            }
            audio == null || audio.isEmpty() -> {
                respondError(call, HttpStatusCode.BadRequest, "the file part is required")
            }
            else -> {
                val jobId = store.enqueue(accountId, audio)
                wakeWorker()
                call.respondText("{\"job_id\":\"$jobId\",\"status\":\"queued\"}", ContentType.Application.Json, HttpStatusCode.Accepted)
            }
        }
    }

    get("/v1/jobs/{job_id}") {
        // Auth first: the poll uses the read-only check (peek); a refused token is one plain 401,
        // the same body as a not-live token, so the caller cannot tell which one happened.
        val accountId = resolveAccount(call, tokenCheck::peek)
        if (accountId == null) {
            respondError(call, HttpStatusCode.Unauthorized, "unauthorized")
            return@get
        }
        val jobId = call.parameters["job_id"]?.toLongOrNull()
        if (jobId == null) {
            respondError(call, HttpStatusCode.NotFound, UNKNOWN_OR_EXPIRED)
            return@get
        }
        when (val poll = store.fetch(jobId, accountId)) {
            is JobPoll.NotFound -> {
                respondError(call, HttpStatusCode.NotFound, UNKNOWN_OR_EXPIRED)
            }
            is JobPoll.Pending -> {
                call.respondText("{\"status\":\"${poll.status.dbValue}\",\"result\":null}", ContentType.Application.Json, HttpStatusCode.OK)
            }
            is JobPoll.Failed -> {
                call.respondText("{\"status\":\"failed\",\"result\":null,\"error\":${escapeJsonString(poll.error)}}", ContentType.Application.Json, HttpStatusCode.OK)
            }
            is JobPoll.Result -> {
                call.respondText("{\"status\":\"done\",\"result\":${poll.text}}", ContentType.Application.Json, HttpStatusCode.OK)
            }
            is JobPoll.ResultGone -> {
                respondError(call, HttpStatusCode.NotFound, UNKNOWN_OR_EXPIRED)
            }
        }
    }

    // Unauthenticated liveness (contract /health, security: []). It echoes the two startup-computed
    // values verbatim - escaped, never re-sanitised or re-formatted here; that work runs once at
    // startup (see startupHealth) and is passed in, so this route is a pure echo.
    get("/health") {
        call.respondText(
            "{\"status\":\"ok\",\"forwarding_to\":${escapeJsonString(forwardingTo)},\"uptime\":${escapeJsonString(uptime())}}",
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }
}

/**
 * The account the presented token resolves to, or null when the request must be refused.
 * The token comes from the Authorization header as `Bearer <token>`; a missing header, a
 * missing or empty token, a not-live token, and an unavailable check all answer null, so the
 * caller sees one plain 401 and cannot tell which of the four happened.
 */
private suspend fun resolveAccount(
    call: ApplicationCall,
    op: suspend (String) -> TokenAnswer,
): Long? {
    val header = call.request.headers["Authorization"] ?: return null
    // The scheme is "Bearer" (case-insensitive per RFC 9110); the token is everything after
    // it, trimmed. Any other scheme, a missing scheme, or an empty token is refused, so the
    // caller sees one plain 401 and cannot tell which of the four happened.
    val schemeEnd = header.indexOf(' ')
    if (schemeEnd <= 0) return null
    if (!header.substring(0, schemeEnd).equals("Bearer", ignoreCase = true)) return null
    val presented = header.substring(schemeEnd + 1).trim()
    if (presented.isEmpty()) return null
    return when (val answer = op(presented)) {
        is TokenAnswer.Live -> answer.accountId
        is TokenAnswer.NotLive -> null
        is TokenAnswer.Unavailable -> null
    }
}

private suspend fun respondError(call: ApplicationCall, status: HttpStatusCode, message: String) {
    call.respondText("{\"error\":${escapeJsonString(message)}}", ContentType.Application.Json, status)
}
