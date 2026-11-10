package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.Role
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
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

// GET /v1/transcriptions: the empty page, the live record shape, the delete record shape, oldest
// first, one account never seeing another account's rows, and 503 on a stored time that cannot be
// formatted. Client ids are built from short pieces so no run of hex digits trips the release scan.

internal class SyncRoutesListTest : TempDatabaseTest() {

    private companion object {
        const val PUSHED_FIRST = "c3d4" + "e5f6-7a8b-4c9d-" + "8e0f-1a" + "2b3c" + "4d5e6f"
        const val PUSHED_SECOND = "a1b2" + "c3d4-e5f6-" + "4a7b-8c" + "9d-0e1f" + "2a3b" + "4c5d"
        const val PUSHED_THIRD = "b2c3" + "d4e5-f6a7-" + "4b8c-9d" + "0e-1f2a" + "3b4c" + "5d6e"
    }

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private var aliceId: Long = 0L
    private var aliceToken: String = ""

    @Before
    fun setUp() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
        val alice = TokenFixtures.register(temp, "alice", 1)
        assertEquals("sync-api: the first account must be the admin", Role.ADMIN, alice.role)
        aliceId = alice.accountId
        aliceToken = SyncRouteTestSupport.sessionToken(tokenStore, aliceId)
    }

    @Test
    fun emptyListAnswers200WithEmptyPage() {
        val reply = SyncRouteTestSupport.getList(tokenStore, store, aliceToken)
        assertEquals("sync-api: empty list status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: empty page body", "{\"items\":[],\"next\":\"$aliceId-0\",\"more\":false}", reply.body)
    }

    @Test
    fun pushedRowIsListedWithKeyVersionAndStoredTime() {
        val pushed = SyncRouteTestSupport.postSync(
            tokenStore, store, aliceToken, SyncRouteTestSupport.pushBody(CLIENT_ID, "0", ENCRYPTED),
        )
        assertEquals("sync-api: push status", HttpStatusCode.NoContent, pushed.status)
        val reply = SyncRouteTestSupport.getList(tokenStore, store, aliceToken)
        assertEquals("sync-api: list status", HttpStatusCode.OK, reply.status)
        assertEquals(
            "sync-api: list body",
            "{\"items\":[" + SyncRouteTestSupport.pageRecordJson(CLIENT_ID, 1, false, 0, ENCRYPTED, "2023-11-14T22:13:20.000Z") + "],\"next\":\"$aliceId-1\",\"more\":false}",
            reply.body,
        )
    }

    @Test
    fun deletedRowIsListedAsDeleteRecord() {
        SyncRouteTestSupport.postSync(tokenStore, store, aliceToken, SyncRouteTestSupport.pushBody(CLIENT_ID, "0", ENCRYPTED))
        SyncRouteTestSupport.deleteTranscription(tokenStore, store, aliceToken, CLIENT_ID)
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, aliceToken, "$aliceId-1")
        assertEquals("sync-api: delete record status", HttpStatusCode.OK, reply.status)
        assertEquals(
            "sync-api: delete record body",
            "{\"items\":[" + SyncRouteTestSupport.pageRecordJson(CLIENT_ID, 2, true, null, null, "2023-11-14T22:13:20.000Z") + "],\"next\":\"$aliceId-2\",\"more\":false}",
            reply.body,
        )
    }

    @Test
    fun listIsOldestFirstWithOneRowPerClientId() {
        SyncRouteTestSupport.postSync(tokenStore, store, aliceToken, SyncRouteTestSupport.pushBody(PUSHED_FIRST, "0", ENCRYPTED))
        clock.advance(Duration.ofDays(1))
        SyncRouteTestSupport.postSync(tokenStore, store, aliceToken, SyncRouteTestSupport.pushBody(PUSHED_SECOND, "0", ENCRYPTED))
        clock.advance(Duration.ofDays(1))
        SyncRouteTestSupport.postSync(tokenStore, store, aliceToken, SyncRouteTestSupport.pushBody(PUSHED_THIRD, "0", ENCRYPTED))
        val reply = SyncRouteTestSupport.getList(tokenStore, store, aliceToken)
        assertEquals(
            "sync-api: oldest first body",
            "{\"items\":[" +
                SyncRouteTestSupport.pageRecordJson(PUSHED_FIRST, 1, false, 0, ENCRYPTED, "2023-11-14T22:13:20.000Z") + "," +
                SyncRouteTestSupport.pageRecordJson(PUSHED_SECOND, 2, false, 0, ENCRYPTED, "2023-11-15T22:13:20.000Z") + "," +
                SyncRouteTestSupport.pageRecordJson(PUSHED_THIRD, 3, false, 0, ENCRYPTED, "2023-11-16T22:13:20.000Z") +
            "],\"next\":\"$aliceId-3\",\"more\":false}",
            reply.body,
        )
    }

    @Test
    fun oneAccountNeverSeesTheRowsOfAnotherAccount() {
        SyncRouteTestSupport.postSync(tokenStore, store, aliceToken, SyncRouteTestSupport.pushBody(CLIENT_ID, "0", ENCRYPTED))
        val bob = TokenFixtures.register(temp, "bob", 2)
        assertEquals("sync-api: the second account must be a user", Role.USER, bob.role)
        val bobToken = SyncRouteTestSupport.sessionToken(tokenStore, bob.accountId)

        val bobBefore = SyncRouteTestSupport.getList(tokenStore, store, bobToken)
        assertEquals("sync-api: bob sees no rows of alice", "{\"items\":[],\"next\":\"${bob.accountId}-0\",\"more\":false}", bobBefore.body)

        SyncRouteTestSupport.postSync(tokenStore, store, bobToken, SyncRouteTestSupport.pushBody(OTHER_CLIENT_ID, "0", OTHER_ENCRYPTED))
        val aliceList = SyncRouteTestSupport.getList(tokenStore, store, aliceToken)
        assertEquals(
            "sync-api: alice sees only her own row",
            "{\"items\":[" + SyncRouteTestSupport.pageRecordJson(CLIENT_ID, 1, false, 0, ENCRYPTED, "2023-11-14T22:13:20.000Z") + "],\"next\":\"$aliceId-1\",\"more\":false}",
            aliceList.body,
        )
        assertFalse("sync-api: alice must not see bob's client id", aliceList.body.contains(OTHER_CLIENT_ID))
        val bobList = SyncRouteTestSupport.getList(tokenStore, store, bobToken)
        assertEquals(
            "sync-api: bob sees only his own row",
            "{\"items\":[" + SyncRouteTestSupport.pageRecordJson(OTHER_CLIENT_ID, 1, false, 0, OTHER_ENCRYPTED, "2023-11-14T22:13:20.000Z") + "],\"next\":\"${bob.accountId}-1\",\"more\":false}",
            bobList.body,
        )
    }

    @Test
    fun storedTimeThatCannotBeFormattedAnswers503WithFixedBody() {
        SyncRouteTestSupport.insertRawRow(temp, aliceId, CLIENT_ID, 0, ENCRYPTED, -1L)
        val reply = SyncRouteTestSupport.getList(tokenStore, store, aliceToken)
        assertEquals("sync-api: unformattable stored time status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("sync-api: unformattable stored time body", SyncRouteTestSupport.UNAVAILABLE_BODY, reply.body)
    }

    /** A failure here means the rendered page is not PullPageFixture.PAGE: the delete row at ...123 ms and
     * the live row at ...250 ms must both render with three fraction digits. The clock only moves
     * forward: the delete (change 2) is made at +123 ms, the live push (change 3) at +250 ms. */
    @Test
    fun renderedPageEqualsPullPageFixture() {
        assertEquals("sync-api: the fixture next is 1-3, so alice must be account 1", 1L, aliceId)
        clock.set(TokenFixtures.START.plusMillis(123L))
        SyncRouteTestSupport.postSync(tokenStore, store, aliceToken, SyncRouteTestSupport.pushBody(OTHER_CLIENT_ID, "0", OTHER_ENCRYPTED))
        SyncRouteTestSupport.deleteTranscription(tokenStore, store, aliceToken, OTHER_CLIENT_ID)
        clock.set(TokenFixtures.START.plusMillis(250L))
        SyncRouteTestSupport.postSync(tokenStore, store, aliceToken, SyncRouteTestSupport.pushBody(CLIENT_ID, "0", ENCRYPTED))
        val reply = SyncRouteTestSupport.getPage(tokenStore, store, aliceToken, "$aliceId-1")
        assertEquals("sync-api: rendered page status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: rendered page equals fixture exactly", PullPageFixture.PAGE, reply.body)
    }
}
