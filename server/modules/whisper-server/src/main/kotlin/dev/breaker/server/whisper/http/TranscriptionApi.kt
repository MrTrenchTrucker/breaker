package dev.breaker.server.whisper.http

import dev.breaker.server.whisper.jobs.JobStore
import io.ktor.http.content.MultiPartData
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.receiveMultipart
import io.ktor.server.routing.routing

/**
 * A TokenCheck that answers Unavailable for every call: when no check is wired, the front
 * refuses every authenticated request (fail closed), it never falls through to an owner path.
 */
internal object FailClosedTokenCheck : TokenCheck {
    override suspend fun use(token: String): TokenAnswer = TokenAnswer.Unavailable
    override suspend fun peek(token: String): TokenAnswer = TokenAnswer.Unavailable
}

class TranscriptionApi internal constructor(
    private val store: JobStore,
    private val wakeWorker: () -> Unit,
    private val readMultipart: suspend ApplicationCall.() -> MultiPartData = { receiveMultipart(formFieldLimit = MAX_AUDIO_BYTES + 1) },
    private val forwardingTo: String = "",
    private val uptime: () -> String = { "0h0m0s" },
    private val tokenCheck: TokenCheck = FailClosedTokenCheck,
) {
    fun install(app: Application) {
        app.routing { transcriptionRoutes(store, wakeWorker, readMultipart, forwardingTo, uptime, tokenCheck) }
    }
}
