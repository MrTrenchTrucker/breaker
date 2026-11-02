package dev.breaker.server.syncapi.handoff

import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import dev.breaker.server.syncapi.tokens.TokenFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

/** Two redeems of one code share one database through its single lane. Both calls start together
 * inside one process; the test waits at most the timeout and starts no other process.
 */
internal class HandoffConcurrencyTest : TempDatabaseTest() {

    private val clock = MutableClock(HandoffFixtures.START)
    private val challenge = HandoffFixtures.CHALLENGE_FOR_SECRET(HandoffFixtures.SECRET)
    private val timeoutMs = 10_000L

    private fun startCode(store: HandoffStore, accountId: Long): String {
        val result = runBlocking { store.start(accountId, challenge, HandoffFixtures.SEALED_PACKAGE_BYTES) }
        if (result !is StartResult.Started) {
            throw AssertionError("sync-api: expected Started but got $result")
        }
        return result.code
    }

    // Both redeems are launched before either is awaited, on the default dispatcher.
    private fun twoRedeems(store: HandoffStore, code: String): List<RedeemResult> =
        runBlocking<List<RedeemResult>> {
            withTimeout<List<RedeemResult>>(timeoutMs) {
                coroutineScope<List<RedeemResult>> {
                    val startSignal = CompletableDeferred<Unit>()
                    val jobs: List<Deferred<RedeemResult>> = (0 until 2).map {
                        async<RedeemResult>(Dispatchers.Default) {
                            startSignal.await()
                            store.redeem(code, HandoffFixtures.SECRET, null)
                        }
                    }
                    startSignal.complete(Unit)
                    jobs.awaitAll()
                }
            }
        }

    @Test
    fun `two redeems of one code at once give exactly one Redeemed and one Refused and one new session`() {
        val temp = openTemp()
        val tokenStore = TokenFixtures.tokenStore(temp, clock)
        val store = HandoffFixtures.handoffStore(temp, tokenStore, clock)
        // The first registered account is the ADMIN.
        val owner = TokenFixtures.register(temp, "alice", 1)

        // Five separate codes, each raced by two redeems, so one lucky ordering cannot pass the test.
        for (attempt in 1..5) {
            val code = startCode(store, owner.accountId)
            val tokensBefore = TokenFixtures.tokenRowCount(temp)
            val results = twoRedeems(store, code)
            val redeemedCount = results.count { result -> result is RedeemResult.Redeemed }
            val refusedCount = results.count { result -> result === RedeemResult.Refused }
            assertEquals("sync-api: Redeemed results for attempt $attempt", 1, redeemedCount)
            assertEquals("sync-api: Refused results for attempt $attempt", 1, refusedCount)
            assertEquals("sync-api: exactly one new session row for attempt $attempt", tokensBefore + 1L, TokenFixtures.tokenRowCount(temp))
            assertEquals("sync-api: the code row is gone after attempt $attempt", 0L, HandoffFixtures.rowCount(temp))
        }
    }
}