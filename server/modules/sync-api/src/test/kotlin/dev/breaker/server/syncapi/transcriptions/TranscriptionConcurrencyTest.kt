package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Test

internal class TranscriptionConcurrencyTest : TempDatabaseTest() {

    @Test
    fun `two concurrent pushes of the same pair leave one row`() {
        val temp = openTemp()
        val store = TranscriptionStore(temp.db, AccountFixtures.fixedClock)
        // The first registered account is the admin; the store does not read roles.
        val alice = TokenFixtures.register(temp, "alice", 1).accountId
        val results: List<PushResult> = runBlocking {
            withTimeout(BOUND_MS) {
                coroutineScope {
                    val jobs: List<Deferred<PushResult>> = (1..PUSHES).map {
                        async(Dispatchers.Default) {
                            store.push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "same")
                        }
                    }
                    jobs.awaitAll()
                }
            }
        }
        // awaitAll rethrows a failure from either push, so a push that threw fails the test here.
        assertEquals(
            "sync-api: both concurrent pushes must return a result",
            PUSHES,
            results.size,
        )
        assertEquals(
            "sync-api: one of the two concurrent pushes must store",
            1,
            results.count { result -> result == PushResult.Stored },
        )
        assertEquals(
            "sync-api: the other concurrent push must be a no-op",
            1,
            results.count { result -> result == PushResult.NoOp },
        )
        assertEquals(
            "sync-api: the pair must be stored once",
            1L,
            TranscriptionFixtures.rowCount(temp, alice),
        )
    }

    private companion object {
        const val PUSHES = 2
        const val BOUND_MS = 5_000L
    }
}
