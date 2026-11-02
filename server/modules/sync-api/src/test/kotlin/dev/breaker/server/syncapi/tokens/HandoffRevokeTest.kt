package dev.breaker.server.syncapi.tokens

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.handoff.HandoffFixtures
import dev.breaker.server.syncapi.handoff.HandoffStore
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.tokens.TokenStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** revokeAllForAccount and revokeOtherSessions delete the account's signin_handoff rows too. */
internal class HandoffRevokeTest : TempDatabaseTest() {

    private val clock = MutableClock(TokenFixtures.START)

    private class World(
        val temp: TempDatabase,
        val tokenStore: TokenStore,
        val store: HandoffStore,
        val alice: Long,
        val bob: Long,
    )

    private fun world(): World {
        val temp = openTemp()
        val tokenStore = TokenFixtures.tokenStore(temp, clock)
        val store = HandoffFixtures.handoffStore(temp, tokenStore, clock)
        val alice = TokenFixtures.register(temp, "alice", 1)
        val bob = TokenFixtures.register(temp, "bob", 2)
        return World(temp, tokenStore, store, alice.accountId, bob.accountId)
    }

    private fun hashOf(seed: Int): ByteArray = ByteArray(32) { seed.toByte() }

    private fun handOffRow(temp: TempDatabase, codeHash: ByteArray, accountId: Long, expiresAtMs: Long) {
        HandoffFixtures.insertRawRow(temp, codeHash, accountId, 0L, expiresAtMs)
    }

    private fun handOffRowCount(temp: TempDatabase, accountId: Long): Long = runBlocking {
        HandoffFixtures.rowCountFor(temp, accountId)
    }

    private fun session(store: TokenStore, accountId: Long) =
        TokenFixtures.issuedOf(runBlocking { store.issueSession(accountId) })

    @Test
    fun `revokeAllForAccount deletes every signin_handoff row of that account and leaves another account's`() {
        val w = world()
        val nowMs = TokenFixtures.START.toEpochMilli()
        handOffRow(w.temp, hashOf(1), w.alice, nowMs + 100_000L)
        handOffRow(w.temp, hashOf(2), w.alice, nowMs + 200_000L)
        handOffRow(w.temp, hashOf(3), w.bob, nowMs + 300_000L)
        session(w.tokenStore, w.alice)

        val revoked = runBlocking { w.tokenStore.revokeAllForAccount(w.alice) }

        assertEquals("sync-api: no handoff row remains for alice", 0L, handOffRowCount(w.temp, w.alice))
        assertEquals("sync-api: bob keeps his handoff row", 1L, handOffRowCount(w.temp, w.bob))
        assertEquals("sync-api: alice's session row is counted as revoked", 1, revoked)
    }

    @Test
    fun `revokeOtherSessions deletes every signin_handoff row of that account and leaves another account's`() {
        val w = world()
        val nowMs = TokenFixtures.START.toEpochMilli()
        handOffRow(w.temp, hashOf(1), w.alice, nowMs + 100_000L)
        handOffRow(w.temp, hashOf(2), w.alice, nowMs + 200_000L)
        handOffRow(w.temp, hashOf(3), w.bob, nowMs + 300_000L)
        session(w.tokenStore, w.alice)

        val revoked = runBlocking { w.tokenStore.revokeOtherSessions(w.alice, 999_999L) }

        assertEquals("sync-api: no handoff row remains for alice", 0L, handOffRowCount(w.temp, w.alice))
        assertEquals("sync-api: bob keeps his handoff row", 1L, handOffRowCount(w.temp, w.bob))
        assertEquals("sync-api: the keep id matches nothing so every session (one) is revoked", 1, revoked)
    }

    @Test
    fun `revokeOtherSessions keeps one session revokes the other and deletes all handoff rows`() {
        val w = world()
        val nowMs = TokenFixtures.START.toEpochMilli()
        handOffRow(w.temp, hashOf(1), w.alice, nowMs + 100_000L)
        handOffRow(w.temp, hashOf(2), w.alice, nowMs + 200_000L)
        val first = session(w.tokenStore, w.alice)
        val second = session(w.tokenStore, w.alice)

        val revoked = runBlocking { w.tokenStore.revokeOtherSessions(w.alice, second.tokenId) }

        assertEquals("sync-api: exactly one session revoked", 1, revoked)
        assertTrue("sync-api: the kept session still resolves", runBlocking { w.tokenStore.resolve(second.secret.reveal()) != null })
        assertFalse("sync-api: the other session does not resolve", runBlocking { w.tokenStore.resolve(first.secret.reveal()) != null })
        assertEquals("sync-api: all alice handoff rows are gone", 0L, handOffRowCount(w.temp, w.alice))
    }

    @Test
    fun `revokeAllForAccount deletes handoff rows even when no session was revoked`() {
        val w = world()
        val nowMs = TokenFixtures.START.toEpochMilli()
        handOffRow(w.temp, hashOf(1), w.alice, nowMs + 100_000L)
        handOffRow(w.temp, hashOf(2), w.alice, nowMs + 200_000L)

        val revoked = runBlocking { w.tokenStore.revokeAllForAccount(w.alice) }

        assertEquals("sync-api: alice's handoff rows are gone", 0L, handOffRowCount(w.temp, w.alice))
        assertEquals("sync-api: no session existed so the count is zero", 0, revoked)
    }

    @Test
    fun `revokeOtherSessions deletes handoff rows even when no session was revoked`() {
        val w = world()
        val nowMs = TokenFixtures.START.toEpochMilli()
        handOffRow(w.temp, hashOf(1), w.alice, nowMs + 100_000L)
        handOffRow(w.temp, hashOf(2), w.alice, nowMs + 200_000L)

        val revoked = runBlocking { w.tokenStore.revokeOtherSessions(w.alice, 999_999L) }

        assertEquals("sync-api: alice's handoff rows are gone", 0L, handOffRowCount(w.temp, w.alice))
        assertEquals("sync-api: the keep id matches nothing so every session (none) is revoked", 0, revoked)
    }

    @Test
    fun `an unknown account id returns zero and leaves its own rows untouched`() {
        val w = world()
        val nowMs = TokenFixtures.START.toEpochMilli()
        handOffRow(w.temp, hashOf(1), w.alice, nowMs + 100_000L)

        assertEquals("sync-api: an unknown account has no token rows", 0, runBlocking { w.tokenStore.revokeAllForAccount(999_999L) })
        assertEquals("sync-api: alice keeps her handoff row", 1L, handOffRowCount(w.temp, w.alice))
        assertEquals("sync-api: an unknown account has no token rows", 0, runBlocking { w.tokenStore.revokeOtherSessions(999_999L, 999_998L) })
        assertEquals("sync-api: alice keeps her handoff row", 1L, handOffRowCount(w.temp, w.alice))
    }
}
