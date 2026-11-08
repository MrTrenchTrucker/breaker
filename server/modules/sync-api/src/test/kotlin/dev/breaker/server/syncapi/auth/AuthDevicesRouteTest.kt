package dev.breaker.server.syncapi.auth
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport
import dev.breaker.server.syncapi.transcriptions.formatInstantMs
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.Duration
internal class AuthDevicesRouteTest : TempDatabaseTest() {
    private lateinit var temp: TempDatabase
    private lateinit var tokenStore: TokenStore
    private val clock = MutableClock(TokenFixtures.START)
    @Before
    fun setUp() {
        temp = openTemp()
        tokenStore = TokenFixtures.tokenStore(temp, clock)
    }
    private fun account(): Long = TokenFixtures.register(temp, "alice", 1).accountId
    private fun bearer(text: String): String = "Bearer $text"
    private fun row(id: Long, current: Boolean, seen: String): String =
        "{\"id\":$id,\"name\":null,\"last_seen\":\"$seen\",\"current\":$current}"

    private fun insertRow(accountId: Long, createdMs: Long, expiresMs: Long?): Long = runBlocking {
        inTransaction(temp) { connection ->
            connection.prepareStatement(
                "INSERT INTO tokens (account_id, kind, scope, label, token_hash, " +
                    "created_at_ms, expires_at_ms, device_name) " +
                    "VALUES (?, 'session', NULL, NULL, ?, ?, ?, NULL)",
            ).use { statement ->
                statement.setLong(1, accountId)
                statement.setBytes(2, ByteArray(32))
                statement.setLong(3, createdMs)
                if (expiresMs == null) statement.setNull(4, java.sql.Types.BIGINT)
                else statement.setLong(4, expiresMs)
                statement.executeUpdate()
                connection.createStatement().use { query ->
                    query.executeQuery("SELECT last_insert_rowid()").use { rows ->
                        check(rows.next()) { "sync-api test: row id missing" }
                        rows.getLong(1)
                    }
                }
            }
        }
    }

    private fun expectBody(expected: String, authorization: String?) {
        val (status, body) = devices(authorization)
        assertEquals("status", HttpStatusCode.OK, status)
        assertEquals("body", expected, body)
    }

    private fun devices(authorization: String?): Pair<HttpStatusCode, String> = runBlocking {
        var status = HttpStatusCode.OK
        var text = ""
        testApplication {
            application { authDevicesRoute(tokenStore) }
            val response = client.get("/v1/auth/devices") {
                if (authorization != null) header(HttpHeaders.Authorization, authorization)
            }
            status = response.status
            text = response.bodyAsText()
        }
        Pair(status, text)
    }

    @Test
    fun `a session lists its own live sessions and marks the current one`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val first = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val second = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val seen = formatInstantMs(TokenFixtures.START.toEpochMilli())
        val expected = "{\"devices\":[" + row(first.tokenId, true, seen) + "," +
            row(second.tokenId, false, seen) + "]}"
        expectBody(expected, bearer(first.secret.reveal()))
    }
    @Test
    fun `a revoked session is not listed`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val first = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val second = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        assertTrue("the revoke must land", runBlocking { tokenStore.revokeSession(second.tokenId) })
        val seen = formatInstantMs(TokenFixtures.START.toEpochMilli())
        expectBody("{\"devices\":[" + row(first.tokenId, true, seen) + "]}", bearer(first.secret.reveal()))
    }
    @Test
    fun `an expired session is not listed`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val first = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val second = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        TokenFixtures.expireSession(temp, second.tokenId)
        val seen = formatInstantMs(TokenFixtures.START.toEpochMilli())
        expectBody("{\"devices\":[" + row(first.tokenId, true, seen) + "]}", bearer(first.secret.reveal()))
    }
    @Test
    fun `a session past the 180 day cap is not listed`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val first = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val created = TokenFixtures.START.toEpochMilli()
        val tooLate = created + Duration.ofDays(180).toMillis() + 1
        insertRow(accountId, created, tooLate)
        val seen = formatInstantMs(created)
        expectBody("{\"devices\":[" + row(first.tokenId, true, seen) + "]}", bearer(first.secret.reveal()))
    }
    @Test
    fun `another account's sessions are never listed`() {
        val alice = account()
        val bob = TokenFixtures.register(temp, "bob", 2).accountId
        clock.set(TokenFixtures.START)
        val first = SyncRouteTestSupport.issuedSession(tokenStore, alice)
        SyncRouteTestSupport.issuedSession(tokenStore, bob)
        val seen = formatInstantMs(TokenFixtures.START.toEpochMilli())
        expectBody("{\"devices\":[" + row(first.tokenId, true, seen) + "]}", bearer(first.secret.reveal()))
    }
    @Test
    fun `an agent token gets 403 and a dead token gets 401`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val live = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val dead = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        assertTrue("the revoke must land", runBlocking { tokenStore.revokeSession(dead.tokenId) })
        val revoked = bearer(dead.secret.reveal())
        val agent = bearer(SyncRouteTestSupport.agentToken(tokenStore, accountId))
        val unauthorized = SyncRouteTestSupport.UNAUTHORIZED_BODY
        val forbidden = SyncRouteTestSupport.FORBIDDEN_BODY
        val basic = "Basic " + live.secret.reveal()
        val unknown = "Bearer " + SyncRouteTestSupport.UNKNOWN_TOKEN
        val cases = listOf(
            Triple(null, HttpStatusCode.Unauthorized, unauthorized),
            Triple(basic, HttpStatusCode.Unauthorized, unauthorized),
            Triple(unknown, HttpStatusCode.Unauthorized, unauthorized),
            Triple(revoked, HttpStatusCode.Unauthorized, unauthorized),
            Triple(agent, HttpStatusCode.Forbidden, forbidden),
        )
        for ((authorization, expectedStatus, expectedBody) in cases) {
            val (status, body) = devices(authorization)
            assertEquals("status", expectedStatus, status)
            assertEquals("body", expectedBody, body)
        }
        val stillLive = runBlocking { tokenStore.resolve(live.secret.reveal()) } != null
        assertTrue("a refused call must not kill the live token", stillLive)
    }
    @Test
    fun `last seen is the expiry minus 30 days and never before the creation`() {
        val alice = account()
        clock.set(TokenFixtures.START)
        val first = SyncRouteTestSupport.issuedSession(tokenStore, alice)
        clock.set(TokenFixtures.START.plus(Duration.ofDays(10)))
        runBlocking { tokenStore.resolve(first.secret.reveal()) }
        val seen = formatInstantMs(clock.current.toEpochMilli())
        expectBody("{\"devices\":[" + row(first.tokenId, true, seen) + "]}", bearer(first.secret.reveal()))
        val bob = TokenFixtures.register(temp, "bob", 2).accountId
        clock.set(TokenFixtures.START.plus(Duration.ofDays(100)))
        val later = SyncRouteTestSupport.issuedSession(tokenStore, bob)
        clock.set(TokenFixtures.START)
        val created = formatInstantMs(TokenFixtures.START.plus(Duration.ofDays(100)).toEpochMilli())
        expectBody("{\"devices\":[" + row(later.tokenId, true, created) + "]}", bearer(later.secret.reveal()))
    }
    @Test
    fun `a session with no expiry reads its creation time`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val first = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val created = TokenFixtures.START.toEpochMilli() + Duration.ofDays(5).toMillis()
        val extraId = insertRow(accountId, created, null)
        val seen = formatInstantMs(TokenFixtures.START.toEpochMilli())
        val createdText = formatInstantMs(created)
        val tail = "{\"id\":$extraId,\"name\":null,\"last_seen\":\"$createdText\",\"current\":false}"
        val expected = "{\"devices\":[" + row(first.tokenId, true, seen) + "," +
            tail + "]}"
        expectBody(expected, bearer(first.secret.reveal()))
    }
    @Test
    fun `a password sign-in session lists a null name and a named one lists its name`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val plain = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val named = inTransaction(temp) { connection ->
            tokenStore.issueSessionOn(connection, accountId, "phone", clock.millis())
        }
        val seen = formatInstantMs(TokenFixtures.START.toEpochMilli())
        val namedRow = "{\"id\":${named.tokenId},\"name\":\"phone\",\"last_seen\":\"$seen\"," +
            "\"current\":false}"
        val expected = "{\"devices\":[" + row(plain.tokenId, true, seen) + "," +
            namedRow + "]}"
        expectBody(expected, bearer(plain.secret.reveal()))
    }
    @Test
    fun `a name with a quote a backslash and a newline is escaped`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val plain = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val weird = "a\"b\\c\n"
        val escaped = "\"a\\\"b\\\\c\\n\""
        val named = inTransaction(temp) { connection ->
            tokenStore.issueSessionOn(connection, accountId, weird, clock.millis())
        }
        val seen = formatInstantMs(TokenFixtures.START.toEpochMilli())
        val weirdRow = "{\"id\":${named.tokenId},\"name\":$escaped,\"last_seen\":\"$seen\"," +
            "\"current\":false}"
        val expected = "{\"devices\":[" + row(plain.tokenId, true, seen) + "," +
            weirdRow + "]}"
        expectBody(expected, bearer(plain.secret.reveal()))
    }
    @Test
    fun `the list is oldest first`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val oldest = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val middle = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val newest = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val seen = formatInstantMs(TokenFixtures.START.toEpochMilli())
        val expected = "{\"devices\":[" + row(oldest.tokenId, false, seen) + "," +
            row(middle.tokenId, true, seen) + "," +
            row(newest.tokenId, false, seen) + "]}"
        expectBody(expected, bearer(middle.secret.reveal()))
    }
    @Test
    fun `51 live sessions answer 50 rows and keep the newest`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val tokens = (1..51).map { SyncRouteTestSupport.issuedSession(tokenStore, accountId) }
        val current = tokens.last()
        val (status, body) = devices(bearer(current.secret.reveal()))
        assertEquals("status", HttpStatusCode.OK, status)
        assertEquals("rows", 50, body.split("\"id\":").size - 1)
        assertFalse("the oldest session is dropped", body.contains("\"id\":${tokens.first().tokenId},"))
        assertTrue("the newest session is kept", body.contains("\"id\":${current.tokenId},"))
        assertEquals("one current row", 1, body.split("\"current\":true").size - 1)
    }
    @Test
    fun `no secret appears in any field`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val first = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val second = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val (status, body) = devices(bearer(first.secret.reveal()))
        assertEquals("status", HttpStatusCode.OK, status)
        assertFalse("the calling secret is absent", body.contains(first.secret.reveal()))
        assertFalse("the other secret is absent", body.contains(second.secret.reveal()))
    }
    @Test
    fun `the row has exactly four members and the answer exactly one`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val first = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val seen = formatInstantMs(TokenFixtures.START.toEpochMilli())
        expectBody("{\"devices\":[" + row(first.tokenId, true, seen) + "]}", bearer(first.secret.reveal()))
    }
    @Test
    fun `a store failure answers the fixed 503`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val first = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        temp.db.close()
        val (status, body) = devices(bearer(first.secret.reveal()))
        assertEquals("status", HttpStatusCode.ServiceUnavailable, status)
        assertEquals("body", SyncRouteTestSupport.UNAVAILABLE_BODY, body)
    }
    @Test
    fun `a name of 64 emoji is listed whole`() {
        val accountId = account()
        clock.set(TokenFixtures.START)
        val plain = SyncRouteTestSupport.issuedSession(tokenStore, accountId)
        val oneChar = "x"
        val emoji = "\uD83D\uDE00".repeat(64)
        val named = inTransaction(temp) { connection ->
            tokenStore.issueSessionOn(connection, accountId, oneChar, clock.millis())
        }
        val emoticon = inTransaction(temp) { connection ->
            tokenStore.issueSessionOn(connection, accountId, emoji, clock.millis())
        }
        val seen = formatInstantMs(TokenFixtures.START.toEpochMilli())
        val middle = "{\"id\":${named.tokenId},\"name\":\"$oneChar\",\"last_seen\":\"$seen\"," +
            "\"current\":false}"
        val last = "{\"id\":${emoticon.tokenId},\"name\":\"$emoji\",\"last_seen\":\"$seen\"," +
            "\"current\":false}"
        val expected = "{\"devices\":[" + row(plain.tokenId, true, seen) + "," +
            middle + "," + last + "]}"
        expectBody(expected, bearer(plain.secret.reveal()))
    }
}
