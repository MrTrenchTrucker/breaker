package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CURSOR_INVALID_BODY
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.UNAUTHORIZED_BODY
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

// Route-level tests of the list cursor. The repro world through the route: 101 live rows
// (changes 1..101), one more push (change 102) and its delete (change 103). Head is 103, so
// page 1 next is acc-100-103 and page 2 next is acc-103. Refusals (position 0 / equal head /
// above head / foreign account) each 400 with accept controls. Auth refused before the cursor
// is read.
// Client ids are 36 characters: CHECK (length(client_id) = 36) in SchemaV3.
// Test names: plain English in backticks, none of . ; [ ] / < > : \ ,, no underscore, no
// parentheses, no test prefix.
internal class ListCursorRouteTest : TempDatabaseTest() {

    private val clock = AccountFixtures.fixedClock

    private fun newStore(temp: TempDatabase): TranscriptionStore =
        SyncRouteTestSupport.newStore(temp, clock)

    private fun newTokenStore(temp: TempDatabase): TokenStore =
        TokenFixtures.tokenStore(temp, clock)

    private fun aliceId(temp: TempDatabase): Long =
        TokenFixtures.register(temp, "alice", 1).accountId

    private fun bobId(temp: TempDatabase): Long =
        TokenFixtures.register(temp, "bob", 2).accountId

    private fun aliceToken(accountId: Long, tokenStore: TokenStore): String =
        SyncRouteTestSupport.sessionToken(tokenStore, accountId)

    private fun bobToken(accountId: Long, tokenStore: TokenStore): String =
        SyncRouteTestSupport.sessionToken(tokenStore, accountId)

    // 101 distinct 36-char ids (12-hex last group at runtime).
    private fun listClientId(index: Int): String =
        "1a2b" + "3c4d-5e6f-" + "4a7b-8c9d-" + java.lang.String.format("%012x", index)

    private fun pushRow(
        temp: TempDatabase,
        store: TranscriptionStore,
        tokenStore: TokenStore,
        token: String,
        clientId: String,
    ) =
        SyncRouteTestSupport.postSync(
            tokenStore, store, token, SyncRouteTestSupport.pushBody(clientId, "0", ENCRYPTED),
        )

    // Build the repro world through the route: 101 live rows, push c102 (change 102), delete c102
    // (change 103). Returns (accountId, token, tokenStore). Head is 103.
    private fun reproWorld(temp: TempDatabase): Triple<Long, String, TokenStore> {
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val accountId = aliceId(temp)
        val token = aliceToken(accountId, tokenStore)
        for (n in 1..101) pushRow(temp, store, tokenStore, token, listClientId(n))
        pushRow(temp, store, tokenStore, token, listClientId(102))                 // change 102, live
        SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, listClientId(102)) // change 103
        return Triple(accountId, token, tokenStore)
    }

    private fun getPage(
        temp: TempDatabase,
        store: TranscriptionStore,
        tokenStore: TokenStore,
        token: String?,
        since: String?,
    ) =
        SyncRouteTestSupport.getPage(tokenStore, store, token, since)

    private fun countClientIds(body: String): Int =
        "\"client_id\"".toRegex().findAll(body).count()

    @Test
    fun `page 1 of a list answers the LIST cursor and page 2 finishes`() {
        val temp = openTemp()
        val (accountId, token, tokenStore) = reproWorld(temp)
        val store = newStore(temp)

        // Page 1 (no since): 100 rows, more true, next is the LIST cursor acc-100-103.
        val page1 = getPage(temp, store, tokenStore, token, null)
        assertEquals("page 1 status", HttpStatusCode.OK, page1.status)
        assertEquals("page 1 holds 100 records", 100, countClientIds(page1.body))
        assertTrue("page 1 more is true", page1.body.contains("\"more\":true"))
        assertTrue("page 1 next is the LIST cursor", page1.body.contains("\"next\":\"$accountId-100-103\""))

        // Page 2 with the LIST cursor: not Gone, one live row change 101, more false,
        // next is the incremental first-page head acc-103.
        val page2 = getPage(temp, store, tokenStore, token, "$accountId-100-103")
        assertEquals("page 2 status", HttpStatusCode.OK, page2.status)
        assertFalse("page 2 is not Gone", page2.status == HttpStatusCode.Gone)
        assertEquals("page 2 holds one record", 1, countClientIds(page2.body))
        assertTrue("page 2 record is change 101", page2.body.contains("\"change\":101"))
        assertTrue("page 2 more is false", page2.body.contains("\"more\":false"))
        assertTrue("page 2 next is incremental head 103", page2.body.contains("\"next\":\"$accountId-103\""))
    }

    @Test
    fun `a list cursor with position 0 is 400`() {
        val temp = openTemp()
        val (accountId, token, tokenStore) = reproWorld(temp)
        val store = newStore(temp)

        val refused = getPage(temp, store, tokenStore, token, "$accountId-0-103")
        assertEquals("position 0 status", HttpStatusCode.BadRequest, refused.status)
        assertEquals("position 0 body", CURSOR_INVALID_BODY, refused.body)

        // Accept control: a valid LIST cursor still works.
        val ok = getPage(temp, store, tokenStore, token, "$accountId-100-103")
        assertEquals("accept control status", HttpStatusCode.OK, ok.status)
    }

    @Test
    fun `a list cursor with position equal to head is 400`() {
        val temp = openTemp()
        val (accountId, token, tokenStore) = reproWorld(temp)
        val store = newStore(temp)

        val refused = getPage(temp, store, tokenStore, token, "$accountId-103-103")
        assertEquals("position equal head status", HttpStatusCode.BadRequest, refused.status)
        assertEquals("position equal head body", CURSOR_INVALID_BODY, refused.body)

        val ok = getPage(temp, store, tokenStore, token, "$accountId-100-103")
        assertEquals("accept control status", HttpStatusCode.OK, ok.status)
    }

    @Test
    fun `a list cursor with position above head is 400`() {
        val temp = openTemp()
        val (accountId, token, tokenStore) = reproWorld(temp)
        val store = newStore(temp)

        val refused = getPage(temp, store, tokenStore, token, "$accountId-104-103")
        assertEquals("position above head status", HttpStatusCode.BadRequest, refused.status)
        assertEquals("position above head body", CURSOR_INVALID_BODY, refused.body)

        val ok = getPage(temp, store, tokenStore, token, "$accountId-100-103")
        assertEquals("accept control status", HttpStatusCode.OK, ok.status)
    }

    @Test
    fun `a list cursor of another account is 400`() {
        val temp = openTemp()
        val (accountId, token, tokenStore) = reproWorld(temp)
        val store = newStore(temp)
        val bobAccount = bobId(temp)
        val bob = bobToken(bobAccount, tokenStore)

        val refused = getPage(temp, store, tokenStore, bob, "$accountId-100-103")
        assertEquals("foreign account status", HttpStatusCode.BadRequest, refused.status)
        assertEquals("foreign account body", CURSOR_INVALID_BODY, refused.body)

        // Accept control: alice's own LIST cursor still works.
        val ok = getPage(temp, store, tokenStore, token, "$accountId-100-103")
        assertEquals("accept control status", HttpStatusCode.OK, ok.status)
    }

    @Test
    fun `no session is refused before the cursor is read`() {
        val temp = openTemp()
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val accountId = aliceId(temp)

        val refused = getPage(temp, store, tokenStore, null, "$accountId-100-103")
        assertEquals("no session status", HttpStatusCode.Unauthorized, refused.status)
        assertEquals("no session body", UNAUTHORIZED_BODY, refused.body)
    }

    @Test
    fun `a revoked session is refused before the cursor is read`() {
        val temp = openTemp()
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val accountId = aliceId(temp)
        val issued = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) }
        )
        assertTrue("session revoked", runBlocking { tokenStore.revokeSession(issued.tokenId) })
        val revokedToken = issued.secret.reveal()

        val refused = getPage(temp, store, tokenStore, revokedToken, "$accountId-100-103")
        assertEquals("revoked session status", HttpStatusCode.Unauthorized, refused.status)
        assertEquals("revoked session body", UNAUTHORIZED_BODY, refused.body)
    }

    @Test
    fun `a new session lists after another session purged past the hundredth row`() {
        val temp = openTemp()
        val (accountId, token, tokenStore) = reproWorld(temp)
        val store = newStore(temp)

        // Session 1 pulls at since=$accountId-103: that pull purges the delete record, purged_through = 103.
        val pull1 = getPage(temp, store, tokenStore, token, "$accountId-103")
        assertEquals("session 1 pull status", HttpStatusCode.OK, pull1.status)
        assertEquals("session 1 pull holds no records", 0, countClientIds(pull1.body))
        val purged1 = inTransaction(temp) { connection ->
            connection.prepareStatement("SELECT purged_through FROM accounts WHERE id = ?").use { s ->
                s.setLong(1, accountId)
                s.executeQuery().use { rows ->
                    check(rows.next()) { "no account" }
                    rows.getLong(1)
                }
            }
        }
        assertEquals("session 1 pull purged_through is 103", 103L, purged1)

        // Session 2 is issued AFTER session 1's purge: its number is null before the list,
        // so nothing is stored on it yet.
        val token2 = TokenFixtures.issuedOf(
            runBlocking { tokenStore.issueSession(accountId) },
        ).secret.reveal()

        // Page 1 with no since: 200, 100 records, next is the LIST cursor acc-100-103.
        val page1 = getPage(temp, store, tokenStore, token2, null)
        assertEquals("session 2 page 1 status", HttpStatusCode.OK, page1.status)
        assertEquals("session 2 page 1 holds 100 records", 100, countClientIds(page1.body))
        assertTrue("session 2 page 1 next is the LIST cursor",
            page1.body.contains("\"next\":\"$accountId-100-103\""))
        assertTrue("session 2 page 1 more is true", page1.body.contains("\"more\":true"))

        // Page 2 with that LIST cursor: 200, one record change 101, next acc-103 (incremental).
        val page2 = getPage(temp, store, tokenStore, token2, "$accountId-100-103")
        assertEquals("session 2 page 2 status", HttpStatusCode.OK, page2.status)
        assertEquals("session 2 page 2 holds one record", 1, countClientIds(page2.body))
        assertTrue("session 2 page 2 more is false", page2.body.contains("\"more\":false"))
        assertTrue("session 2 page 2 next is head 103",
            page2.body.contains("\"next\":\"$accountId-103\""))
    }

    @Test
    fun `an incremental route page that has more answers the incremental cursor`() {
        val temp = openTemp()
        val store = newStore(temp)
        val tokenStore = newTokenStore(temp)
        val accountId = aliceId(temp)
        val token = aliceToken(accountId, tokenStore)
        // 150 fresh live rows (changes 1..150).
        for (n in 1..150) pushRow(temp, store, tokenStore, token, listClientId(n))
        // Delete row 120 at change 151, head moves to 151.
        SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, listClientId(120))

        // Page 1 with since=$accountId-10: 100 records (changes 11..110), more true,
        // next $accountId-110, NO list cursor (incremental).
        val page1 = getPage(temp, store, tokenStore, token, "$accountId-10")
        assertEquals("page 1 status", HttpStatusCode.OK, page1.status)
        assertEquals("page 1 holds 100 records", 100, countClientIds(page1.body))
        assertTrue("page 1 more is true", page1.body.contains("\"more\":true"))
        assertTrue("page 1 next is incremental 110", page1.body.contains("\"next\":\"$accountId-110\""))
        assertFalse("page 1 next is not a LIST cursor",
            page1.body.contains("$accountId-110-"))

        // Page 2 with since=$accountId-110: records 111..151 (40 rows incl. delete of c120),
        // all fit in one page, more false, listHead null, next = head 151.
        val page2 = getPage(temp, store, tokenStore, token, "$accountId-110")
        assertEquals("page 2 status", HttpStatusCode.OK, page2.status)
        assertTrue("page 2 more is false", page2.body.contains("\"more\":false"))
        assertFalse("page 2 has no list cursor",
            page2.body.contains("-110-") || page2.body.contains("-151-"))
    }
}
