package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

// a session token is capped at an absolute 180 days from creation. The slide never
// pushes its expiry past created_at_ms + 180d, and the live-check guard refuses a session
// whose expiry is past the cap. Agent tokens are not capped (their expiry is set at issue and
// is not slid), so a long-lived agent token stays live.
//
// A session issued at START (day 0) has created_at_ms = day 0 and its cap at day 180. The
// slide on each use is min(now + 30d, created + 180d). A use at day N (while live) moves the
// expiry to min(day N + 30, day 180). The cap binds when day N + 30 > 180, i.e. N > 150.
internal class TokenStoreSessionCapTest : TempDatabaseTest() {

    private lateinit var temp: TempDatabase
    private lateinit var clock: MutableClock
    private lateinit var store: TokenStore
    private var userId: Long = 0L

    private val day: Long = 24L * 60 * 60 * 1000

    @Before
    fun openStore() {
        temp = openTemp()
        clock = MutableClock(TokenFixtures.START)
        store = TokenFixtures.tokenStore(temp, clock)
        userId = TokenFixtures.register(temp, "member", 2).accountId
    }

    private fun session(): IssuedToken {
        val result = runBlocking { store.issueSession(userId) }
        return TokenFixtures.issuedOf(result)
    }

    private fun agent(expiresAtDays: Long): IssuedToken =
        TokenFixtures.issuedOf(
            runBlocking {
                store.issueAgent(userId, AgentScope.TRANSCRIBE, "bot", clock.instant().plusMillis(expiresAtDays * day))
            },
        )

    private fun resolve(text: String): ResolvedToken? = runBlocking { store.resolve(text) }

    private fun atDay(n: Long) = clock.set(TokenFixtures.START.plusMillis(n * day))

    private fun expiresAt(id: Long): Long? =
        inTransaction<Long?>(temp) { connection ->
            connection.prepareStatement("SELECT expires_at_ms FROM tokens WHERE id = ?").use { statement ->
                statement.setLong(1, id)
                statement.executeQuery().use { rows ->
                    check(rows.next()) { "sync-api test: no token row with id $id" }
                    val value = rows.getLong(1)
                    if (rows.wasNull()) null else value
                }
            }
        }

    // Keep the session alive to day 174 with a 29-day cadence (each use strictly before the
    // previous expiry, so every resolve is live and slides). At day 174 the slide would set
    // now + 30d = day 204, but the cap clamps it to day 180.
    private fun keepAliveTo(issued: IssuedToken, upToDay: Long) {
        for (useDay in 29L..upToDay step 29) {
            atDay(useDay)
            assertNotNull("the session is live at day $useDay (before its slide)", resolve(issued.secret.reveal()))
        }
    }

    @Test
    fun `a session kept alive past the 180 day cap is refused at the cap`() {
        val issued = session()
        keepAliveTo(issued, 174L)
        // At day 174 the slide was clamped to the cap (day 180), not day 204.
        assertEquals(
            "the slide at day 174 clamps to created + 180d (day 180), not now + 30d (day 204)",
            TokenFixtures.START.toEpochMilli() + 180L * day,
            expiresAt(issued.tokenId),
        )
        // Past the cap: at day 181 the expiry (day 180) is not after now, so the token is refused.
        atDay(181)
        assertNull("a session past the 180-day cap is refused", resolve(issued.secret.reveal()))
    }

    @Test
    fun `the slide clamps a session to the 180 day cap instead of now plus 30 days`() {
        val issued = session()
        keepAliveTo(issued, 174L)
        // now + 30d at day 174 is day 204; the cap is day 180. The stored expiry must be the cap.
        assertEquals(
            "the slide clamps to created + 180d (day 180), not now + 30d (day 204)",
            TokenFixtures.START.toEpochMilli() + 180L * day,
            expiresAt(issued.tokenId),
        )
    }

    @Test
    fun `an agent token past 180 days stays live`() {
        // An agent token issued with a 400-day expiry is past the 180-day session cap, but it is
        // not a session: the live-check guard is scoped to sessions, so it stays live.
        val issued = agent(400L)
        atDay(200)
        assertNotNull(
            "an agent token past 180 days stays live (the cap is session-scoped)",
            resolve(issued.secret.reveal()),
        )
    }

    @Test
    fun `a session inside the cap still slides to now plus 30 days`() {
        val issued = session()
        atDay(29)
        assertNotNull("live at day 29 (within the cap)", resolve(issued.secret.reveal()))
        assertEquals(
            "inside the cap the slide is now + 30d (day 59), the cap not binding",
            TokenFixtures.START.toEpochMilli() + 59L * day,
            expiresAt(issued.tokenId),
        )
    }
}
