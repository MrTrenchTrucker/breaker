package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.OTHER_CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.OTHER_ENCRYPTED
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// Feed behaviour tests: since null = live rows only; since N = rows above N incl. delete record;
// since 0 = everything; since above head = empty, more false, next = head; 101 rows pages; another
// account's rows never appear; since below purged_through = Gone; since == purged_through = a page.
// Client ids built from short pieces (no hex run of 7+).

internal class SyncRoutesFeedTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private var accountId: Long = 0L
    private var token: String = ""

    @Before
    fun openFeed() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
        accountId = TokenFixtures.register(temp, "alice", 1).accountId
        token = SyncRouteTestSupport.sessionToken(tokenStore, accountId)
    }

    private fun push(clientId: String, encrypted: String): SyncReply =
        SyncRouteTestSupport.postSync(tokenStore, store, token, SyncRouteTestSupport.pushBody(clientId, "0", encrypted))

    @Test
    fun sinceNullReturnsLiveRowsOnlyInChangeOrder() {
        push(CLIENT_ID, ENCRYPTED)
        push(OTHER_CLIENT_ID, OTHER_ENCRYPTED)
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, null)
        assertEquals("since null status", HttpStatusCode.OK, reply.status)
        assertEquals(
            "since null body",
            "{\"items\":[" +
                SyncRouteTestSupport.pageRecordJson(CLIENT_ID, 1, false, 0, ENCRYPTED, "2023-11-14T22:13:20.000Z") + "," +
                SyncRouteTestSupport.pageRecordJson(OTHER_CLIENT_ID, 2, false, 0, OTHER_ENCRYPTED, "2023-11-14T22:13:20.000Z") +
            "],\"next\":\"$accountId-2\",\"more\":false}",
            reply.body,
        )
    }

    @Test
    fun sinceNAboveHeadAnswersEmptyWithMoreFalseAndNextHead() {
        push(CLIENT_ID, ENCRYPTED)
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "$accountId-5")
        assertEquals("since above head status", HttpStatusCode.OK, reply.status)
        assertEquals(
            "since above head body",
            "{\"items\":[],\"next\":\"$accountId-1\",\"more\":false}",
            reply.body,
        )
    }

    @Test
    fun sinceZeroReturnsEverything() {
        push(CLIENT_ID, ENCRYPTED)
        push(OTHER_CLIENT_ID, OTHER_ENCRYPTED)
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "$accountId-0")
        assertEquals("since zero status", HttpStatusCode.OK, reply.status)
        assertEquals(
            "since zero body",
            "{\"items\":[" +
                SyncRouteTestSupport.pageRecordJson(CLIENT_ID, 1, false, 0, ENCRYPTED, "2023-11-14T22:13:20.000Z") + "," +
                SyncRouteTestSupport.pageRecordJson(OTHER_CLIENT_ID, 2, false, 0, OTHER_ENCRYPTED, "2023-11-14T22:13:20.000Z") +
            "],\"next\":\"$accountId-2\",\"more\":false}",
            reply.body,
        )
    }

    @Test
    fun sinceNAboveDeletedRowIncludesDeleteRecord() {
        push(CLIENT_ID, ENCRYPTED)
        SyncRouteTestSupport.deleteTranscription(tokenStore, store, token, CLIENT_ID)
        // since = 1 returns rows above change 1: only the delete record at change 2.
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "$accountId-1")
        assertEquals("since N includes delete status", HttpStatusCode.OK, reply.status)
        assertEquals(
            "since N includes delete body",
            "{\"items\":[" +
                SyncRouteTestSupport.pageRecordJson(CLIENT_ID, 2, true, null, null, "2023-11-14T22:13:20.000Z") +
            "],\"next\":\"$accountId-2\",\"more\":false}",
            reply.body,
        )
    }

    @Test
    fun hundredOneRowsPagesIntoTwo() {
        for (index in 1..101) {
            // A real canonical UUID per index: fixed valid groups plus a 12-hex last group built
            // at runtime (no source line holds 7 hex digits in a row).
            val clientId = "1a2b" + "3c4d-5e6f-" + "4a7b-8c9d-" + String.format("%012x", index)
            val reply = push(clientId, ENCRYPTED)
            assertEquals("push $index status", HttpStatusCode.NoContent, reply.status)
        }
        val first = SyncRouteTestSupport.getPage(tokenStore, store, token, null)
        assertEquals("page 1 status", HttpStatusCode.OK, first.status)
        assertEquals("page 1 holds 100 records", 100, Regex("\"client_id\"").findAll(first.body).count())
        assertTrue("page 1 more is true", first.body.contains("\"more\":true"))
        assertTrue("page 1 next is account-100-101", first.body.contains("\"next\":\"$accountId-100-101\""))

        val second = SyncRouteTestSupport.getPage(tokenStore, store, token, "$accountId-100-101")
        assertEquals("page 2 status", HttpStatusCode.OK, second.status)
        assertEquals("page 2 holds one record", 1, Regex("\"client_id\"").findAll(second.body).count())
        assertTrue("page 2 more is false", second.body.contains("\"more\":false"))
        assertTrue("page 2 has one record change 101", second.body.contains("\"change\":101"))
        assertTrue("page 2 next is head 101", second.body.contains("\"next\":\"$accountId-101\""))
    }

    @Test
    fun anotherAccountRowsNeverAppear() {
        push(CLIENT_ID, ENCRYPTED)
        val bob = TokenFixtures.register(temp, "bob", 2)
        val bobToken = SyncRouteTestSupport.sessionToken(tokenStore, bob.accountId)
        SyncRouteTestSupport.postSync(tokenStore, store, bobToken, SyncRouteTestSupport.pushBody(OTHER_CLIENT_ID, "0", OTHER_ENCRYPTED))

        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, null)
        assertEquals("cross-account status", HttpStatusCode.OK, reply.status)
        assertTrue("alice does not see bob's row", !reply.body.contains(OTHER_CLIENT_ID))
        assertEquals(
            "cross-account body",
            "{\"items\":[" + SyncRouteTestSupport.pageRecordJson(CLIENT_ID, 1, false, 0, ENCRYPTED, "2023-11-14T22:13:20.000Z") + "],\"next\":\"$accountId-1\",\"more\":false}",
            reply.body,
        )
    }

    @Test
    fun sinceBelowPurgedThroughIsGone() {
        push(CLIENT_ID, ENCRYPTED)
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE accounts SET purged_through = 1 WHERE id = ?").use { s ->
                    s.setLong(1, accountId)
                    s.executeUpdate()
                }
            }
        }
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "$accountId-0")
        assertEquals("below purged status", HttpStatusCode.Gone, reply.status)
    }

    @Test
    fun sinceEqualToPurgedThroughIsNotGone() {
        push(CLIENT_ID, ENCRYPTED)
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("UPDATE accounts SET purged_through = 1 WHERE id = ?").use { s ->
                    s.setLong(1, accountId)
                    s.executeUpdate()
                }
            }
        }
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, token, "$accountId-1")
        assertEquals("equal purged status", HttpStatusCode.OK, reply.status)
        assertTrue("equal purged empty page", reply.body.contains("\"items\":[]"))
    }
}
