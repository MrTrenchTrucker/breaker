package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.tokens.AgentScope
import dev.breaker.server.syncapi.tokens.IssuedToken
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import java.time.Clock
import kotlinx.coroutines.runBlocking

// The reply of one request: the status and the body text.
internal class SyncReply(val status: HttpStatusCode, val body: String)

// Shared fixtures for the sync route tests. A test passes every value it varies explicitly, and a
// helper never derives its fake from the value under test.
internal object SyncRouteTestSupport {
    const val CLIENT_ID = "0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6f"
    const val OTHER_CLIENT_ID = "1b0d5e2f-7b3c-4d4e-8f9a-2b3c4d5e6f70"
    const val ENCRYPTED = "aGVsbG8="
    const val OTHER_ENCRYPTED = "d29ybGQ="
    // Standard base64 with a slash: the base64 of two 0xFF bytes, with one padding character.
    const val SLASH_ENCRYPTED = "//8="
    const val CAP: Int = 1024 * 1024

    const val UNAUTHORIZED_BODY =
        """{"error":"unauthorized","message":"Invalid or missing user session token"}"""
    const val FORBIDDEN_BODY =
        """{"error":"forbidden","message":"User session token required; an agent token is refused"}"""
    const val UNAVAILABLE_BODY =
        """{"error":"unavailable","message":"The service is unavailable, try again later"}"""
    const val TOO_LARGE_BODY =
        """{"error":"payload_too_large","message":"Request body exceeds maximum allowed size"}"""
    const val ABOVE_ACCOUNT_BODY =
        """{"error":"conflict","message":"key_version is above the account's current key_version"}"""
    const val LOWER_REFUSED_BODY =
        """{"error":"conflict","message":"A lower key_version is refused for this client_id"}"""

    // 43 characters of the token alphabet: the right shape, and no session carries it.
    val UNKNOWN_TOKEN: String = "C".repeat(43)

    fun newStore(temp: TempDatabase, clock: Clock = AccountFixtures.fixedClock): TranscriptionStore =
        TranscriptionStore(temp.db, clock)

    // The issued token, with its id for a revoke and its secret for a request.
    fun issuedSession(tokenStore: TokenStore, accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(runBlocking { tokenStore.issueSession(accountId) })

    fun issuedAgent(tokenStore: TokenStore, accountId: Long): IssuedToken =
        TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueAgent(accountId, AgentScope.TRANSCRIBE, "sync-label", null) },
        )

    fun sessionToken(tokenStore: TokenStore, accountId: Long): String =
        issuedSession(tokenStore, accountId).secret.reveal()

    fun agentToken(tokenStore: TokenStore, accountId: Long): String =
        issuedAgent(tokenStore, accountId).secret.reveal()

    // A transcription row written by direct SQL, for a value the store itself never writes.
    fun insertRawRow(
        temp: TempDatabase,
        accountId: Long,
        clientId: String,
        keyVersion: Int,
        encrypted: String,
        storedAtMs: Long,
    ) {
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "INSERT INTO transcriptions (account_id, client_id, key_version, encrypted, stored_at_ms) " +
                        "VALUES (?, ?, ?, ?, ?)",
                ).use { statement ->
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

    fun pushBody(clientId: String, keyVersion: String, encrypted: String): String =
        """{"client_id":"$clientId","key_version":$keyVersion,"encrypted":"$encrypted"}"""

    // A valid body of exactly [size] characters: the padding is spaces before the closing brace.
    fun bodyOfSize(size: Int): String {
        val base = pushBody(CLIENT_ID, "0", ENCRYPTED)
        require(base.length <= size) { "sync-api test helper: the size is below the smallest body" }
        return base.dropLast(1) + " ".repeat(size - base.length) + "}"
    }

    fun rowJson(clientId: String, keyVersion: Int, encrypted: String, storedAt: String): String =
        """{"client_id":"$clientId","key_version":$keyVersion,"encrypted":"$encrypted","stored_at":"$storedAt"}"""

    // A body sent without a declared length (a chunked request), the way AuthBodyLimitTest sends its
    // chunked case: the content type is set, and no Content-Length is written by the test client.
    fun postChunkedSync(tokenStore: TokenStore, store: TranscriptionStore, token: String, bytes: ByteArray): SyncReply {
        var reply: SyncReply? = null
        runBlocking {
            testApplication {
                application { syncRoutes(tokenStore, store) }
                val response = client.post("/v1/sync") {
                    header("Authorization", "Bearer $token")
                    setBody(object : OutgoingContent.ReadChannelContent() {
                        override fun readFrom(): ByteReadChannel = ByteReadChannel(bytes)
                    })
                    contentType(ContentType.Application.Json)
                }
                reply = SyncReply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }

    fun postSync(tokenStore: TokenStore, store: TranscriptionStore, token: String?, body: String): SyncReply =
        postSyncWithAuthorization(tokenStore, store, token?.let { "Bearer $it" }, body)

    // Sends the Authorization header exactly as given, or no header at all when it is null.
    fun postSyncWithAuthorization(
        tokenStore: TokenStore,
        store: TranscriptionStore,
        authorization: String?,
        body: String,
    ): SyncReply {
        var reply: SyncReply? = null
        runBlocking {
            testApplication {
                application { syncRoutes(tokenStore, store) }
                val response = client.post("/v1/sync") {
                    if (authorization != null) header("Authorization", authorization)
                    setBody(body)
                    contentType(ContentType.Application.Json)
                }
                reply = SyncReply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }

    fun getList(tokenStore: TokenStore, store: TranscriptionStore, token: String?): SyncReply =
        getListWithAuthorization(tokenStore, store, token?.let { "Bearer $it" })

    // Sends the Authorization header exactly as given, or no header at all when it is null.
    fun getListWithAuthorization(
        tokenStore: TokenStore,
        store: TranscriptionStore,
        authorization: String?,
    ): SyncReply {
        var reply: SyncReply? = null
        runBlocking {
            testApplication {
                application { syncRoutes(tokenStore, store) }
                val response = client.get("/v1/transcriptions") {
                    if (authorization != null) header("Authorization", authorization)
                }
                reply = SyncReply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }
}
