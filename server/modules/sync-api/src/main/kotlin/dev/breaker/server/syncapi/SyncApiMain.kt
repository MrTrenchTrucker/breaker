package dev.breaker.server.syncapi

import dev.breaker.server.syncapi.db.SqliteDatabase
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlin.system.exitProcess

// Not covered by unit tests (it needs a socket); everything it calls is.
fun main() {
    val config: SyncApiConfig = try {
        SyncApiConfig.fromEnv(System.getenv())
    } catch (e: IllegalArgumentException) {
        System.err.println(e.message)
        exitProcess(2)
    }
    // Opening the file also brings the schema up to the current version.
    val db = SqliteDatabase.open(config.dbPath)
    embeddedServer(CIO, host = config.host, port = config.port) {
        syncApiModule()
        monitor.subscribe(ApplicationStopped) { db.close() }
    }.start(wait = true)
}
