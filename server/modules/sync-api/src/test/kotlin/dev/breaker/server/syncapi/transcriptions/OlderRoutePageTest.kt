package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.CLIENT_ID
import dev.breaker.server.syncapi.transcriptions.SyncRouteTestSupport.ENCRYPTED
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// GET /v1/transcriptions/older through the route, for the page cut at fifty rows and for a row whose
// key version is not zero. Stored times are the start plus an offset in milliseconds; the expected
// stored_at texts are literals.
internal class OlderRoutePageTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var tokenStore: TokenStore
    private lateinit var store: TranscriptionStore
    private var aliceId: Long = 0L
    private lateinit var aliceToken: String
    private val base: Long = TokenFixtures.START.toEpochMilli()

    @Before
    fun setUp() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        tokenStore = TokenFixtures.tokenStore(temp, clock)
        store = SyncRouteTestSupport.newStore(temp, clock)
        aliceId = TokenFixtures.register(temp, "alice", 1).accountId
        aliceToken = SyncRouteTestSupport.sessionToken(tokenStore, aliceId)
    }

    private fun clientIdOf(index: Int): String = "0000" + "0000-0000-4000-8000-" + String.format("%012d", index)

    // Stores a live row for alice at [offsetMs] after START.
    private fun pushAt(offsetMs: Long, clientId: String) {
        clock.set(TokenFixtures.START.plusMillis(offsetMs))
        val pushed = runBlocking { store.push(aliceId, clientId, 0, ENCRYPTED) }
        assertEquals("sync-api: the row is stored", PushResult.Stored, pushed)
    }

    private fun getOlderPage(olderThan: String): SyncReply =
        OlderRouteTestSupport.getOlder(tokenStore, store, "Bearer $aliceToken", olderThan)

    // Fails when the route writes the more flag as a constant false: a page cut at fifty rows answers true.
    // Fifty one rows are stored; the page keeps the newest fifty, so row two is the last one on the page.
    @Test
    fun `a page cut at fifty rows answers more true and the next of its last row`() {
        for (index in 1..51) pushAt(index * 1_000L, clientIdOf(index))
        val reply = getOlderPage("${base + 100_000L}")
        assertEquals("sync-api: the page is served", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: fifty records on the page", 50, reply.body.split("\"client_id\"").size - 1)
        assertTrue(
            "sync-api: the page ends with the next of row two and more true",
            reply.body.endsWith("\"next\":\"${base + 2_000L}-2\",\"more\":true}"),
        )
        assertFalse("sync-api: row one is not on the page", reply.body.contains(clientIdOf(1)))
    }

    // Fails when the route writes key_version as a constant zero instead of the stored key version of the row.
    // The raw row is stored at key version 3 with change number 0, the column default.
    @Test
    fun `a row with a non zero key version is rendered with it`() {
        SyncRouteTestSupport.insertRawRow(temp, aliceId, CLIENT_ID, 3, ENCRYPTED, base + 1_000L)
        val reply = getOlderPage("${base + 10_000L}")
        assertEquals("sync-api: the raw row page is served", HttpStatusCode.OK, reply.status)
        assertEquals(
            "sync-api: the raw row is rendered with key version 3",
            "{\"items\":[" +
                SyncRouteTestSupport.pageRecordJson(CLIENT_ID, 0L, false, 3, ENCRYPTED, "2023-11-14T22:13:21.000Z") +
                "],\"next\":\"${base + 1_000L}-0\",\"more\":false}",
            reply.body,
        )
    }
}
