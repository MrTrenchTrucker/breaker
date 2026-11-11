package dev.breaker.server.syncapi

import dev.breaker.server.syncapi.accounts.AccountStore
import dev.breaker.server.syncapi.accounts.RateLimitCounterStore
import dev.breaker.server.syncapi.accounts.runRateLimitCounterSweep
import dev.breaker.server.syncapi.accounts.runSigninAttemptSweep
import dev.breaker.server.syncapi.db.SqliteDatabase
import dev.breaker.server.syncapi.handoff.HandoffStore
import dev.breaker.server.syncapi.handoff.runHandoffSweep
import dev.breaker.server.syncapi.reset.ResetStore
import dev.breaker.server.syncapi.servicecheck.ServiceKeyFile
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import dev.breaker.server.syncapi.transcriptions.ExpiryScheduler
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import java.nio.file.Paths
import java.security.SecureRandom
import kotlin.system.exitProcess
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

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
    val counterStore = RateLimitCounterStore(db, accountStore.clock)
    val tokenStore = TokenStore(db)
    val transcriptionStore = TranscriptionStore(db)
    val handoffStore = HandoffStore(db, tokenStore)
    val resetStore = ResetStore(accountStore, tokenStore)
    // The key file sits next to the database, so it is read only after the database has opened.
    val serviceKey: String = try {
        ServiceKeyFile.loadOrCreate(Paths.get(config.serviceKeyFile), SecureRandom())
    } catch (e: IllegalStateException) {
        db.close()
        System.err.println(e.message)
        exitProcess(2)
    }
    // One job runs three sweeps: the hand-off sweep deletes expired sign-in codes and expired reset
    // codes (at boot, then every minute), the attempt sweep deletes dead wrong-password rows (at boot,
    // then every five minutes), and the expiry scheduler turns transcriptions older than the retention
    // TTL into delete records (once a day). Cancelling the job stops all three. It starts once the
    // stores exist and stops in shutdownInOrder, before the database closes.
    val sweepJob = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
        launch { runHandoffSweep(handoffStore, resetStore) }
        launch { runSigninAttemptSweep(accountStore.attempts) }
        launch { runRateLimitCounterSweep(counterStore) }
        launch { ExpiryScheduler.runExpiryScheduler(transcriptionStore, accountStore) }
    }
    // The internal listener starts first and carries only the token-check routes.
    val internalServer = embeddedServer(CIO, host = config.internalHost, port = config.internalPort) {
        internalModule(tokenStore, serviceKey)
    }
    try {
        internalServer.start(wait = false)
    } catch (_: Exception) {
        runBlocking { sweepJob.cancelAndJoin() }
        db.close()
        System.err.println("sync-api: the internal listener could not start")
        exitProcess(1)
    }
    val publicServer = embeddedServer(CIO, host = config.host, port = config.port) {
        syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore, config.manifestFile, config.gitTag, config.whisperHealthUrl)
    }
    // When the public listener stops, or fails to start, the internal listener stops, then the sweep
    // stops, and then the database closes, exactly once: no token check or sweep can reach a closed
    // database.
    try {
        publicServer.start(wait = true)
    } finally {
        try {
            shutdownInOrder(
                stopInternal = { internalServer.stop(1000, 5000) },
                closeDatabase = { db.close() },
                stopSweep = { runBlocking { sweepJob.cancelAndJoin() } },
            )
        } catch (_: Exception) {
            System.err.println("sync-api: a shutdown step failed")
        }
    }
}
