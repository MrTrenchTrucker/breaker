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

// GET /v1/transcriptions: the empty list, the row shape with the stored time from the injected clock,
// oldest first, and one account never seeing another account's rows.
internal class SyncRoutesListTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private var aliceId: Long = 0L
    private var aliceToken: String = ""

    // Three full client ids. They are pushed in the order FIRST, SECOND, THIRD, which is not their
    // alphabetical order: SECOND starts with a, THIRD with b, and FIRST with c.
    private companion object {
        const val PUSHED_FIRST = "c3d4e5f6-7a8b-4c9d-8e0f-1a2b3c4d5e6f"
        const val PUSHED_SECOND = "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5d"
        const val PUSHED_THIRD = "b2c3d4e5-f6a7-4b8c-9d0e-1f2a3b4c5d6e"
    }

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
    fun `an empty list answers 200 with an empty array`() {
        val reply = SyncRouteTestSupport.getList(tokenStore, store, aliceToken)
        assertEquals("sync-api: empty list status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: empty list body", "[]", reply.body)
    }

    @Test
    fun `a pushed row is listed with its key version, its text and the stored time of the clock`() {
        val pushed = SyncRouteTestSupport.postSync(
            tokenStore, store, aliceToken, SyncRouteTestSupport.pushBody(CLIENT_ID, "0", ENCRYPTED),
        )
        assertEquals("sync-api: push status", HttpStatusCode.NoContent, pushed.status)
        val reply = SyncRouteTestSupport.getList(tokenStore, store, aliceToken)
        assertEquals("sync-api: list status", HttpStatusCode.OK, reply.status)
        assertEquals(
            "sync-api: list body",
            "[" + SyncRouteTestSupport.rowJson(CLIENT_ID, 0, ENCRYPTED, "2023-11-14T22:13:20Z") + "]",
            reply.body,
        )
    }

    @Test
    fun `the list is oldest first with one row for each client id`() {
        // The ids are pushed in the order c, a, b. Their alphabetical order is a, b, c, so a list that
        // sorted by client id would not give the insertion order asserted below.
        SyncRouteTestSupport.postSync(
            tokenStore, store, aliceToken, SyncRouteTestSupport.pushBody(PUSHED_FIRST, "0", ENCRYPTED),
        )
        clock.advance(Duration.ofDays(1))
        SyncRouteTestSupport.postSync(
            tokenStore, store, aliceToken, SyncRouteTestSupport.pushBody(PUSHED_SECOND, "0", ENCRYPTED),
        )
        clock.advance(Duration.ofDays(1))
        SyncRouteTestSupport.postSync(
            tokenStore, store, aliceToken, SyncRouteTestSupport.pushBody(PUSHED_THIRD, "0", ENCRYPTED),
        )
        val reply = SyncRouteTestSupport.getList(tokenStore, store, aliceToken)
        assertEquals(
            "sync-api: oldest first body",
            "[" +
                SyncRouteTestSupport.rowJson(PUSHED_FIRST, 0, ENCRYPTED, "2023-11-14T22:13:20Z") +
                "," +
                SyncRouteTestSupport.rowJson(PUSHED_SECOND, 0, ENCRYPTED, "2023-11-15T22:13:20Z") +
                "," +
                SyncRouteTestSupport.rowJson(PUSHED_THIRD, 0, ENCRYPTED, "2023-11-16T22:13:20Z") +
                "]",
            reply.body,
        )
    }

    @Test
    fun `one account never sees the rows of another account`() {
        SyncRouteTestSupport.postSync(
            tokenStore, store, aliceToken, SyncRouteTestSupport.pushBody(CLIENT_ID, "0", ENCRYPTED),
        )
        val bob = TokenFixtures.register(temp, "bob", 2)
        assertEquals("sync-api: the second account must be a user", Role.USER, bob.role)
        val bobToken = SyncRouteTestSupport.sessionToken(tokenStore, bob.accountId)

        val bobBefore = SyncRouteTestSupport.getList(tokenStore, store, bobToken)
        assertEquals("sync-api: bob sees no rows of alice", "[]", bobBefore.body)

        SyncRouteTestSupport.postSync(
            tokenStore, store, bobToken, SyncRouteTestSupport.pushBody(OTHER_CLIENT_ID, "0", OTHER_ENCRYPTED),
        )
        val aliceList = SyncRouteTestSupport.getList(tokenStore, store, aliceToken)
        assertEquals(
            "sync-api: alice sees only her own row",
            "[" + SyncRouteTestSupport.rowJson(CLIENT_ID, 0, ENCRYPTED, "2023-11-14T22:13:20Z") + "]",
            aliceList.body,
        )
        assertFalse("sync-api: alice must not see bob's client id", aliceList.body.contains(OTHER_CLIENT_ID))
        val bobList = SyncRouteTestSupport.getList(tokenStore, store, bobToken)
        assertEquals(
            "sync-api: bob sees only his own row",
            "[" + SyncRouteTestSupport.rowJson(OTHER_CLIENT_ID, 0, OTHER_ENCRYPTED, "2023-11-14T22:13:20Z") + "]",
            bobList.body,
        )
    }

    // A stored time before 1970 cannot be formatted. The store holds it (the column has no check), and the
    // list answers the fixed 503 body, not a 500.
    @Test
    fun `a stored time that cannot be formatted answers 503 with the fixed body`() {
        SyncRouteTestSupport.insertRawRow(temp, aliceId, CLIENT_ID, 0, ENCRYPTED, -1L)
        val reply = SyncRouteTestSupport.getList(tokenStore, store, aliceToken)
        assertEquals("sync-api: unformattable stored time status", HttpStatusCode.ServiceUnavailable, reply.status)
        assertEquals("sync-api: unformattable stored time body", SyncRouteTestSupport.UNAVAILABLE_BODY, reply.body)
    }
}
