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

/** The one owner every job is enqueued under until auth lands with sync-api. */
internal const val OWNER_ACCOUNT_ID: Long = 1L

/** The single 404 reason for a GET the caller cannot satisfy: an unknown id, a non-numeric id, or a
 *  done job whose result has passed its TTL. One string, so the caller always sees the same reason. */
internal const val UNKNOWN_OR_EXPIRED = "unknown or expired"


/** The upload cap, in bytes. Source: the OpenAI audio-transcriptions API limit - this front is deliberately OpenAI-shaped (module card). */
internal const val MAX_AUDIO_BYTES: Long = 25L * 1024 * 1024

internal fun Route.transcriptionRoutes(
    store: JobStore,
    wakeWorker: () -> Unit,
    readMultipart: suspend ApplicationCall.() -> MultiPartData = { receiveMultipart(formFieldLimit = MAX_AUDIO_BYTES + 1) },
    forwardingTo: String = "",
    uptime: () -> String = { "0h0m0s" },
) {
    post("/v1/audio/transcriptions") {
        var readAudio: ByteArray? = null
        var readModel: String? = null
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
                        readModel = part.value
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
        val model = readModel

        when {
            tooLarge -> {
                respondError(call, HttpStatusCode.PayloadTooLarge, "the audio is larger than the limit")
            }
            audio == null || audio.isEmpty() -> {
                respondError(call, HttpStatusCode.BadRequest, "the file part is required")
            }
            model == null || model.isBlank() -> {
                respondError(call, HttpStatusCode.BadRequest, "the model part is required")
            }
            else -> {
                val jobId = store.enqueue(OWNER_ACCOUNT_ID, audio)
                wakeWorker()
                call.respondText("{\"job_id\":$jobId,\"status\":\"queued\"}", ContentType.Application.Json, HttpStatusCode.Accepted)
            }
        }
    }

    get("/v1/jobs/{job_id}") {
        val jobId = call.parameters["job_id"]?.toLongOrNull()
        if (jobId == null) {
            respondError(call, HttpStatusCode.NotFound, UNKNOWN_OR_EXPIRED)
            return@get
        }
        when (val poll = store.fetch(jobId, OWNER_ACCOUNT_ID)) {
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

private suspend fun respondError(call: ApplicationCall, status: HttpStatusCode, message: String) {
    call.respondText("{\"error\":${escapeJsonString(message)}}", ContentType.Application.Json, status)
}
