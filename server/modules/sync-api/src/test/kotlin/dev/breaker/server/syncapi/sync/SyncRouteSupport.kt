package dev.breaker.server.syncapi.sync

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.TranscriptionFixtures
import dev.breaker.server.syncapi.transcriptions.TranscriptionStore
import dev.breaker.shared.api.sync.SyncBody
import dev.breaker.shared.api.sync.SyncBodyCodec
import dev.breaker.shared.api.sync.SyncSource
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.server.application.Application
import io.ktor.server.routing.routing
import java.util.Base64
import kotlinx.coroutines.runBlocking

internal const val SYNC_PATH = "/v1/sync"
internal const val PULL_PATH = "/v1/transcriptions"
internal const val CLIENT_ID = "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f"
internal const val SECOND_CLIENT_ID = "1a2b3c4d-5e6f-4a7b-8c9d-0e1f2a3b4c5d"

/** The stored_at of a row written at the fixture clock start, in the pull's whole-second form. */
internal const val START_STORED_AT = "2023-11-14T22:13:20Z"

internal const val UNAUTHORIZED_SESSION_BODY =
    """{"error":"unauthorized","message":"Invalid or missing user session token"}"""
internal const val FORBIDDEN_AGENT_BODY =
    """{"error":"forbidden","message":"User session token required; an agent token is refused"}"""
internal const val BAD_REQUEST_BODY =
    """{"error":"bad_request","message":"The sync push body is not a valid transcription push."}"""
internal const val PAYLOAD_TOO_LARGE_BODY =
    """{"error":"payload_too_large","message":"The sync push is larger than the allowed size."}"""
internal const val CONFLICT_BODY =
    """{"error":"conflict","message":"A key_version above the account's current key_version is refused"}"""

internal fun utf8(text: String): ByteArray = text.toByteArray(Charsets.UTF_8)

internal fun bearer(token: String): String = "Bearer $token"

/** A push body with the key version written exactly as given, so a test can send a malformed number. */
internal fun pushBody(clientId: String, keyVersion: String, encrypted: String): String =
    """{"client_id":"$clientId","key_version":$keyVersion,"encrypted":"$encrypted"}"""

/** The key_version 0 form of a valid sync body with the given text. */
internal fun validEncrypted(text: String = "hello"): String =
    SyncBodyCodec.encodePlain(SyncBody(text, SyncSource.LOCAL, "base.en", 1500L, 1_700_000_000_000L))

internal fun encryptedOf(text: String): String = Base64.getEncoder().encodeToString(utf8(text))

/** Base64 of [size] zero bytes: a string whose decoded length is [size] (not a valid body). */
internal fun encryptedOfSize(size: Int): String = Base64.getEncoder().encodeToString(ByteArray(size))

internal fun syncBodyJson(text: String, source: String = "local"): String =
    """{"v":1,"text":"$text","source":"$source","model":"base.en","duration_ms":1500,""" +
        """"created_at":"2023-11-14T22:13:20.000Z"}"""

/** A valid sync body of exactly [size] bytes: the JSON object with spaces after its opening brace. */
internal fun syncBodyOfSize(size: Int, text: String = "hello"): String {
    val base = syncBodyJson(text)
    return "{" + " ".repeat(size - base.length) + base.substring(1)
}

internal suspend fun HttpClient.postSync(authorization: String?, body: ByteArray): HttpResponse =
    post(SYNC_PATH) {
        if (authorization != null) header(HttpHeaders.Authorization, authorization)
        contentType(ContentType.Application.Json)
        setBody(body)
    }

internal suspend fun HttpClient.pullSync(authorization: String?): HttpResponse =
    get(PULL_PATH) {
        if (authorization != null) header(HttpHeaders.Authorization, authorization)
    }

/** Shared set-up of the route tests: one temporary database, a fixed-start clock, and the two stores. */
internal abstract class SyncRouteTestBase : TempDatabaseTest() {
    protected lateinit var temp: TempDatabase
    protected lateinit var tokenStore: TokenStore
    protected lateinit var transcriptionStore: TranscriptionStore
    protected lateinit var clock: MutableClock

    protected fun openSyncStores() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        transcriptionStore = TranscriptionFixtures.store(temp, clock)
    }

    protected fun account(name: String, seed: Int): Long = TokenFixtures.register(temp, name, seed).accountId

    protected fun issueSession(accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) })

    protected fun issueAgent(accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueAgent(accountId, AgentScope.TRANSCRIBE, "agent-label", null) },
        )

    protected fun revoke(token: IssuedToken) {
        runBlocking { tokenStore.revokeSession(token.tokenId) }
    }

    protected fun expire(token: IssuedToken) {
        TokenFixtures.expireSession(temp, token.tokenId)
    }

    protected fun storedRowCount(): Long = TranscriptionFixtures.rowCount(temp)

    /** Writes a row directly, so a test can hold a key version the route itself refuses to store. */
    protected fun insertRow(accountId: Long, clientId: String, keyVersion: Int, encrypted: String, storedAtMs: Long) {
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(INSERT_ROW_SQL).use { statement ->
                    statement.setLong(1, accountId)
                    statement.setString(2, clientId)
                    statement.setInt(3, keyVersion)
                    statement.setString(4, encrypted)
                    statement.setLong(5, storedAtMs)
                    statement.executeUpdate()
                }
            }
        }
    }

    protected fun Application.installSyncRoutes() {
        routing {
            syncPushRoute(tokenStore, transcriptionStore)
            transcriptionsPullRoute(tokenStore, transcriptionStore)
        }
    }

    private companion object {
        const val INSERT_ROW_SQL =
            "INSERT INTO transcriptions (account_id, client_id, key_version, encrypted, stored_at_ms) " +
                "VALUES (?, ?, ?, ?, ?)"
    }
}
