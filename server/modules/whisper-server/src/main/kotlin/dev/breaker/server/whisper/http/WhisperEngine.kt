package dev.breaker.server.whisper.http

import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import kotlinx.coroutines.runBlocking

/**
 * Starts the Ktor engine. All io.ktor imports live here, never in the module root.
 */
internal object WhisperEngine {
    fun start(api: TranscriptionApi, host: String, port: Int, onShutdown: suspend () -> Unit): EmbeddedServer<*, *> {
        val server = embeddedServer(CIO, port = port, host = host) {
            api.install(this)
        }
        Runtime.getRuntime().addShutdownHook(Thread { runBlocking { onShutdown() } })
        server.start(wait = false)
        return server
    }
}
