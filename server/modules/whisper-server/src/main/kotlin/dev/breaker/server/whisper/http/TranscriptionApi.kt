package dev.breaker.server.whisper.http

import dev.breaker.server.whisper.jobs.JobStore
import io.ktor.server.application.Application
import io.ktor.server.routing.routing

class TranscriptionApi internal constructor(
    private val store: JobStore,
    private val wakeWorker: () -> Unit,
) {
    fun install(app: Application) {
        app.routing { transcriptionRoutes(store, wakeWorker) }
    }
}
