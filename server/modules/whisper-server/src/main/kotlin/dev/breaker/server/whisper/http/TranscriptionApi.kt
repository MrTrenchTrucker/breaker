package dev.breaker.server.whisper.http

import dev.breaker.server.whisper.jobs.JobStore
import io.ktor.http.content.MultiPartData
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.routing.routing

class TranscriptionApi internal constructor(
    private val store: JobStore,
    private val wakeWorker: () -> Unit,
    private val readMultipart: suspend ApplicationCall.() -> MultiPartData = { receiveMultipart(formFieldLimit = MAX_AUDIO_BYTES + 1) },
    private val forwardingTo: String = "",
    private val uptime: () -> String = { "0h0m0s" },
) {
    fun install(app: Application) {
        app.routing { transcriptionRoutes(store, wakeWorker, readMultipart, forwardingTo, uptime) }
    }
}
