package dev.breaker.server.syncapi

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.db.SqliteDatabase
import dev.breaker.server.syncapi.servicecheck.ServiceKeyFile
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.nio.file.Paths
import java.security.SecureRandom
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
    val accountStore = AccountStore(db)
    val tokenStore = TokenStore(db)
    val transcriptionStore = TranscriptionStore(db)
    // The key file sits next to the database, so it is read only after the database has opened.
    val serviceKey: String = try {
        ServiceKeyFile.loadOrCreate(Paths.get(config.serviceKeyFile), SecureRandom())
    } catch (e: IllegalStateException) {
        db.close()
        System.err.println(e.message)
        exitProcess(2)
    }
    // The internal listener starts first and carries only the token-check routes.
    val internalServer = embeddedServer(CIO, host = config.internalHost, port = config.internalPort) {
        internalModule(tokenStore, serviceKey)
    }
    try {
        internalServer.start(wait = false)
    } catch (_: Exception) {
        db.close()
        System.err.println("sync-api: the internal listener could not start")
        exitProcess(1)
    }
    val publicServer = embeddedServer(CIO, host = config.host, port = config.port) {
        syncApiModule(accountStore, tokenStore, transcriptionStore)
    }
    // When the public listener stops, or fails to start, the internal listener stops and then the
    // database closes, exactly once: no token check can reach a closed database.
    try {
        publicServer.start(wait = true)
    } finally {
        try {
            shutdownInOrder(
                stopInternal = { internalServer.stop(1000, 5000) },
                closeDatabase = { db.close() },
            )
        } catch (_: Exception) {
            System.err.println("sync-api: a shutdown step failed")
        }
    }
}
