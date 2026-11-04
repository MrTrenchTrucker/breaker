package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.executeStatement
import dev.breaker.server.syncapi.syncApiModule
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.OutgoingContent
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import java.util.Base64
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull

/** The three key members with distinct patterns, so a swap of two members changes the bytes. Fresh arrays on every call. */
internal object KeyPatterns {
    const val REKEYED_AT_MS: Long = 1_700_000_000_000L
    const val RESET_AT_MS: Long = 1_700_000_000_123L

    fun wrappedDek(): ByteArray = ByteArray(60) { i -> (i * 3 + 1).toByte() }

    fun wrappedBoxPrivkey(): ByteArray = ByteArray(60) { i -> (i * 5 + 2).toByte() }

    fun boxPubkey(): ByteArray = ByteArray(32) { i -> (i * 7 + 3).toByte() }

    fun prevWrappedDek(): ByteArray = ByteArray(60) { i -> (i * 11 + 4).toByte() }

    /** Standard alphabet with padding, the form the wire uses. */
    fun text(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)
}

/** The key columns of one account row, read straight from the table. */
internal class KeyColumns(
    val wrappedDek: ByteArray?,
    val wrappedBoxPrivkey: ByteArray?,
    val boxPubkey: ByteArray?,
    val keyVersion: Int,
    val prevWrappedDek: ByteArray?,
    val rekeyedAt: Long?,
    val resetAt: Long?,
)

internal fun readKeyColumns(temp: TempDatabase, accountId: Long): KeyColumns = runBlocking {
    temp.db.transaction { connection ->
        connection.prepareStatement(
            "SELECT wrapped_dek, wrapped_box_privkey, box_pubkey, key_version, prev_wrapped_dek, rekeyed_at, reset_at " +
                "FROM accounts WHERE id = ?",
        ).use { statement ->
            statement.setLong(1, accountId)
            statement.executeQuery().use { rows ->
                check(rows.next()) { "sync-api test helper: no account with id $accountId" }
                val dek = rows.getBytes(1)
                val privkey = rows.getBytes(2)
                val pubkey = rows.getBytes(3)
                val version = rows.getInt(4)
                val previous = rows.getBytes(5)
                val rekeyed = rows.getLong(6).let { value -> if (rows.wasNull()) null else value }
                val reset = rows.getLong(7).let { value -> if (rows.wasNull()) null else value }
                KeyColumns(dek, privkey, pubkey, version, previous, rekeyed, reset)
            }
        }
    }
}

/** Runs one statement written by the test (never input) in its own transaction. */
internal fun runSql(temp: TempDatabase, sql: String) {
    runBlocking { temp.db.transaction { connection -> connection.executeStatement(sql) } }
}

/** Writes prev_wrapped_dek directly, because the schema has no other writer yet. */
internal fun presetPrev(temp: TempDatabase, accountId: Long, bytes: ByteArray) {
    runBlocking {
        temp.db.transaction { connection ->
            connection.prepareStatement("UPDATE accounts SET prev_wrapped_dek = ? WHERE id = ?").use { statement ->
                statement.setBytes(1, bytes)
                statement.setLong(2, accountId)
                statement.executeUpdate()
            }
        }
    }
}

/** The shared set-up of the keys route tests: the key bytes and texts, request helpers and column checks. */
internal abstract class AuthKeysRouteTestBase : HandoffRouteTestBase() {

    protected val wrappedDekBytes: ByteArray = KeyPatterns.wrappedDek()
    protected val wrappedBoxPrivkeyBytes: ByteArray = KeyPatterns.wrappedBoxPrivkey()
    protected val boxPubkeyBytes: ByteArray = KeyPatterns.boxPubkey()
    protected val wrappedDekText: String = KeyPatterns.text(wrappedDekBytes)
    protected val wrappedBoxPrivkeyText: String = KeyPatterns.text(wrappedBoxPrivkeyBytes)
    protected val boxPubkeyText: String = KeyPatterns.text(boxPubkeyBytes)

    protected val keysPath = "/v1/auth/keys"
    protected val conflictBody = """{"error":"conflict","message":"The account already has keys"}"""
    protected val tooLargeBody = """{"error":"payload_too_large","message":"Request body exceeds maximum allowed size"}"""
    protected val forcedFailureTrigger =
        "CREATE TRIGGER forced_keys_failure AFTER UPDATE ON accounts " +
            "WHEN NEW.wrapped_dek IS NOT NULL BEGIN SELECT RAISE(ABORT, 'forced'); END"

    protected fun bodyOf(dek: String, privkey: String, pubkey: String): String =
        """{"wrapped_dek":"$dek","wrapped_box_privkey":"$privkey","box_pubkey":"$pubkey"}"""

    protected fun validBody(): String = bodyOf(wrappedDekText, wrappedBoxPrivkeyText, boxPubkeyText)

    /** The body padded with trailing spaces to exactly [size] bytes (the body is ASCII and ends in a closing brace). */
    protected fun padTo(body: String, size: Int): String {
        require(body.length <= size) { "sync-api test helper: body of ${body.length} is above $size" }
        return body + " ".repeat(size - body.length)
    }

    protected fun postKeys(bearer: String?, body: String): Reply = postTo(keysPath, bearer, body)

    /** Sends the Authorization header exactly as given, or none when it is null. */
    protected fun postKeysWithAuthorization(authorization: String?, body: String): Reply {
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post(keysPath) {
                    if (authorization != null) header(HttpHeaders.Authorization, authorization)
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }

    /** A body sent without a declared length (a chunked request). */
    protected fun postKeysChunked(bearer: String?, bytes: ByteArray): Reply {
        var reply: Reply? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post(keysPath) {
                    if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
                    setBody(object : OutgoingContent.ReadChannelContent() {
                        override fun readFrom(): ByteReadChannel = ByteReadChannel(bytes)
                    })
                    contentType(ContentType.Application.Json)
                }
                reply = Reply(response.status, response.bodyAsText())
            }
        }
        return checkNotNull(reply)
    }

    /** The Content-Type header of the answer to one keys request. */
    protected fun contentTypeOfAnswer(bearer: String?, body: String): String {
        var found: String? = null
        runBlocking {
            testApplication {
                application { syncApiModule(accountStore, tokenStore, transcriptionStore, handoffStore) }
                val response = client.post(keysPath) {
                    if (bearer != null) header(HttpHeaders.Authorization, "Bearer $bearer")
                    contentType(ContentType.Application.Json)
                    setBody(body)
                }
                found = response.headers[HttpHeaders.ContentType]
            }
        }
        return checkNotNull(found)
    }

    protected fun agentText(accountId: Long): String = SyncRouteTestSupport.agentToken(tokenStore, accountId)

    protected fun columnsOf(accountId: Long): KeyColumns = readKeyColumns(temp, accountId)

    protected fun assertKeyless(accountId: Long, label: String) {
        val columns = columnsOf(accountId)
        assertNull("sync-api: $label left wrapped_dek set", columns.wrappedDek)
        assertNull("sync-api: $label left wrapped_box_privkey set", columns.wrappedBoxPrivkey)
        assertNull("sync-api: $label left box_pubkey set", columns.boxPubkey)
        assertEquals("sync-api: $label changed key_version", 0, columns.keyVersion)
    }

    /** The three columns hold the base patterns; key_version is [version]. */
    protected fun assertStored(accountId: Long, version: Int, label: String) {
        val columns = columnsOf(accountId)
        assertArrayEquals("sync-api: $label wrapped_dek", wrappedDekBytes, columns.wrappedDek)
        assertArrayEquals("sync-api: $label wrapped_box_privkey", wrappedBoxPrivkeyBytes, columns.wrappedBoxPrivkey)
        assertArrayEquals("sync-api: $label box_pubkey", boxPubkeyBytes, columns.boxPubkey)
        assertEquals("sync-api: $label key_version", version, columns.keyVersion)
    }
}
