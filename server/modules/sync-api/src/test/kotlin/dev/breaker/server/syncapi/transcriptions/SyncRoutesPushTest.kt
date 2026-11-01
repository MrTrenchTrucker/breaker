package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.Role
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ABOVE_ACCOUNT_BODY
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.TOO_LARGE_BODY
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.pushBody
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

// POST /v1/sync: the status of each push, the fixed 400 for each body validation failure, the 413 at the cap and
// the 409 above the account's key_version. The first registered account is the admin.
internal class SyncRoutesPushTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private var accountId: Long = 0L
    private var token: String = ""

    @Before
    fun setUp() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
        val owner = TokenFixtures.register(temp, "alice", 1)
        assertEquals("sync-api: the first account must be the admin", Role.ADMIN, owner.role)
        accountId = owner.accountId
        token = SyncRouteTestSupport.sessionToken(tokenStore, accountId)
    }

    private fun push(body: String): SyncReply = SyncRouteTestSupport.postSync(tokenStore, store, token, body)

    private fun storedCount(): Int = runBlocking { store.list(accountId) }.size

    @Test
    fun `a first push answers 204 with no body`() {
        val reply = push(pushBody(CLIENT_ID, "0", ENCRYPTED))
        assertEquals("sync-api: first push status", HttpStatusCode.NoContent, reply.status)
        assertEquals("sync-api: a 204 carries no body", "", reply.body)
        assertEquals("sync-api: the first push must store one row", 1, storedCount())
    }

    @Test
    fun `a repeat of the same pair answers 204 and keeps one row`() {
        push(pushBody(CLIENT_ID, "0", ENCRYPTED))
        val again = push(pushBody(CLIENT_ID, "0", ENCRYPTED))
        assertEquals("sync-api: repeat push status", HttpStatusCode.NoContent, again.status)
        assertEquals("sync-api: the repeat must not add a row", 1, storedCount())
    }

    @Test
    fun `a body of exactly the cap is accepted`() {
        assertEquals(
            "sync-api: the test cap must be the route cap",
            SyncRouteTestSupport.CAP.toLong(),
            SYNC_BODY_CAP_BYTES,
        )
        val reply = push(SyncRouteTestSupport.bodyOfSize(SyncRouteTestSupport.CAP))
        assertEquals("sync-api: a body of exactly the cap", HttpStatusCode.NoContent, reply.status)
    }

    @Test
    fun `a body one byte over the cap is refused with 413 and stores nothing`() {
        val reply = push(SyncRouteTestSupport.bodyOfSize(SyncRouteTestSupport.CAP + 1))
        assertEquals("sync-api: one byte over the cap", HttpStatusCode.PayloadTooLarge, reply.status)
        assertEquals("sync-api: the 413 body", TOO_LARGE_BODY, reply.body)
        assertEquals("sync-api: a refused body stores nothing", 0, storedCount())
    }

    // The declared-length guard cannot see this body: the client sends it without a Content-Length,
    // so only the stream guard in receiveSyncBody can refuse it.
    @Test
    fun `a chunked body one byte over the cap is refused with 413 and stores nothing`() {
        val bytes = SyncRouteTestSupport.bodyOfSize(SyncRouteTestSupport.CAP + 1).toByteArray()
        val reply = SyncRouteTestSupport.postChunkedSync(tokenStore, store, token, bytes)
        assertEquals("sync-api: chunked one over the cap", HttpStatusCode.PayloadTooLarge, reply.status)
        assertEquals("sync-api: the chunked 413 body", TOO_LARGE_BODY, reply.body)
        assertEquals("sync-api: a refused chunked body stores nothing", 0, storedCount())
    }

    // The standard alphabet includes the slash, so this text must be accepted and then listed as sent.
    @Test
    fun `a push whose text holds a slash is stored and listed`() {
        val pushed = push(pushBody(CLIENT_ID, "0", SyncRouteTestSupport.SLASH_ENCRYPTED))
        assertEquals("sync-api: slash text push status", HttpStatusCode.NoContent, pushed.status)
        val listed = SyncRouteTestSupport.getList(tokenStore, store, token)
        assertEquals(
            "sync-api: slash text list body",
            """[{"client_id":"$CLIENT_ID","key_version":0,"encrypted":"//8=","stored_at":"2023-11-14T22:13:20Z"}]""",
            listed.body,
        )
    }

    @Test
    fun `each malformed push answers 400 with its fixed message and stores nothing`() {
        val cases = listOf(
            Triple("an array", "[]", MESSAGE_BODY_SHAPE),
            Triple(
                "a truncated object",
                "{\"client_id\":\"$CLIENT_ID\",\"key_version\":0,\"encrypted\":\"$ENCRYPTED\"",
                MESSAGE_BODY_SHAPE,
            ),
            Triple(
                "an extra key",
                """{"client_id":"$CLIENT_ID","key_version":0,"encrypted":"$ENCRYPTED","extra":1}""",
                MESSAGE_BODY_SHAPE,
            ),
            Triple("a missing key", """{"client_id":"$CLIENT_ID","key_version":0}""", MESSAGE_BODY_SHAPE),
            Triple(
                "a duplicate key",
                """{"client_id":"$CLIENT_ID","key_version":0,"key_version":0,"encrypted":"$ENCRYPTED"}""",
                MESSAGE_BODY_SHAPE,
            ),
            Triple(
                "text after the object",
                """{"client_id":"$CLIENT_ID","key_version":0,"encrypted":"$ENCRYPTED"} x""",
                MESSAGE_BODY_SHAPE,
            ),
            Triple("a byte order mark", "\uFEFF" + pushBody(CLIENT_ID, "0", ENCRYPTED), MESSAGE_BODY_SHAPE),
            Triple(
                "an upper case client id",
                pushBody("0F9C4D1E-6A2B-4C3D-9E8F-1A2B3C4D5E6F", "0", ENCRYPTED),
                MESSAGE_CLIENT_ID,
            ),
            Triple(
                "a short client id",
                pushBody("0f9c4d1e-6a2b-4c3d-9e8f-1a2b3c4d5e6", "0", ENCRYPTED),
                MESSAGE_CLIENT_ID,
            ),
            Triple(
                "a client id that is not a string",
                """{"client_id":5,"key_version":0,"encrypted":"$ENCRYPTED"}""",
                MESSAGE_CLIENT_ID,
            ),
            Triple("a key version that is a string", pushBody(CLIENT_ID, "\"0\"", ENCRYPTED), MESSAGE_KEY_VERSION),
            Triple("a negative key version", pushBody(CLIENT_ID, "-1", ENCRYPTED), MESSAGE_KEY_VERSION),
            Triple("a key version with a leading zero", pushBody(CLIENT_ID, "01", ENCRYPTED), MESSAGE_KEY_VERSION),
            Triple("a key version with a fraction", pushBody(CLIENT_ID, "1.0", ENCRYPTED), MESSAGE_KEY_VERSION),
            Triple("a key version with an exponent", pushBody(CLIENT_ID, "1e0", ENCRYPTED), MESSAGE_KEY_VERSION),
            Triple(
                "a key version above the maximum",
                pushBody(CLIENT_ID, (Int.MAX_VALUE.toLong() + 1L).toString(), ENCRYPTED),
                MESSAGE_KEY_VERSION,
            ),
            Triple("a key version that is true", pushBody(CLIENT_ID, "true", ENCRYPTED), MESSAGE_KEY_VERSION),
            Triple("an empty encrypted text", pushBody(CLIENT_ID, "0", ""), MESSAGE_ENCRYPTED),
            Triple("an encrypted text with a space", pushBody(CLIENT_ID, "0", "aGVs bG8="), MESSAGE_ENCRYPTED),
            Triple("an encrypted text in URL-safe characters", pushBody(CLIENT_ID, "0", "a-_w"), MESSAGE_ENCRYPTED),
            Triple("a hyphen alone", pushBody(CLIENT_ID, "0", "a-aw"), MESSAGE_ENCRYPTED),
            Triple("an underscore alone", pushBody(CLIENT_ID, "0", "a_aw"), MESSAGE_ENCRYPTED),
            Triple("an encrypted text without padding", pushBody(CLIENT_ID, "0", "aGVsbG8"), MESSAGE_ENCRYPTED),
            Triple(
                "an encrypted text with padding in the middle",
                pushBody(CLIENT_ID, "0", "aGVsbG=8"),
                MESSAGE_ENCRYPTED,
            ),
            Triple(
                "an encrypted value that is not a string",
                """{"client_id":"$CLIENT_ID","key_version":0,"encrypted":5}""",
                MESSAGE_ENCRYPTED,
            ),
        )
        for ((label, body, message) in cases) {
            val reply = push(body)
            assertEquals("sync-api: $label status", HttpStatusCode.BadRequest, reply.status)
            assertEquals("sync-api: $label body", """{"error":"bad_request","message":"$message"}""", reply.body)
        }
        assertEquals("sync-api: a refused push must store nothing", 0, storedCount())
    }

    @Test
    fun `a push above the account key version is refused with 409 and the new text`() {
        val reply = push(pushBody(CLIENT_ID, "1", ENCRYPTED))
        assertEquals("sync-api: above the account status", HttpStatusCode.Conflict, reply.status)
        assertEquals("sync-api: above the account body", ABOVE_ACCOUNT_BODY, reply.body)
        assertEquals("sync-api: a refused push stores nothing", 0, storedCount())
    }

    @Test
    fun `the largest key version parses and reaches the account check`() {
        val reply = push(pushBody(CLIENT_ID, Int.MAX_VALUE.toString(), ENCRYPTED))
        assertEquals("sync-api: the largest key version status", HttpStatusCode.Conflict, reply.status)
        assertEquals("sync-api: the largest key version body", ABOVE_ACCOUNT_BODY, reply.body)
    }

    // The account is at key version 1: a push at version 1 is stored, and the same client id at version 0
    // is below the version it holds, so the route must answer 409 with the lower-version text, which
    // is checked here at route level. The above-account 409 has its own test, above this one.
    @Test
    fun `a push below the stored key version of the same client is refused with 409 and the lower text`() {
        TranscriptionFixtures.setKeyVersion(temp, accountId, 1)
        val first = push(pushBody(CLIENT_ID, "1", ENCRYPTED))
        assertEquals("sync-api: version 1 first push status", HttpStatusCode.NoContent, first.status)
        val lower = push(pushBody(CLIENT_ID, "0", ENCRYPTED))
        assertEquals("sync-api: lower refused status", HttpStatusCode.Conflict, lower.status)
        assertEquals("sync-api: lower refused body", SyncRouteTestSupport.LOWER_REFUSED_BODY, lower.body)
        assertEquals("sync-api: a refused lower push keeps one row", 1, storedCount())
    }

    // The text ends in two padding characters, the branch of the padding check that counts two.
    // QQ== is the standard base64 of one byte, 0x41, and is valid.
    @Test
    fun `a push whose text ends in two padding characters is stored and listed`() {
        val pushed = push(pushBody(CLIENT_ID, "0", "QQ=="))
        assertEquals("sync-api: two padding push status", HttpStatusCode.NoContent, pushed.status)
        val listed = SyncRouteTestSupport.getList(tokenStore, store, token)
        assertEquals(
            "sync-api: two padding list body",
            "[" + SyncRouteTestSupport.rowJson(CLIENT_ID, 0, "QQ==", "2023-11-14T22:13:20Z") + "]",
            listed.body,
        )
    }
}
