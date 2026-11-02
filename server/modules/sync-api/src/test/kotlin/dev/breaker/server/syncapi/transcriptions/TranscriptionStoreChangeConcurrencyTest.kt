package dev.breaker.server.syncapi.transcriptions

import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.TokenFixtures
import dev.breaker.server.syncapi.transcriptions.TranscriptionFixtures
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

internal class TranscriptionStoreChangeConcurrencyTest : TempDatabaseTest() {

    @Test
    fun `two concurrent pushes of different client ids give two different consecutive numbers`() {
        val temp = openTemp()
        val store = TranscriptionStore(temp.db)
        // The first registered account is the admin; the store does not read roles.
        val alice = TokenFixtures.register(temp, "alice", 1).accountId

        val results: List<PushResult> = runBlocking {
            coroutineScope {
                val jobs: List<kotlinx.coroutines.Deferred<PushResult>> = listOf(
                    async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                        store.push(alice, TranscriptionFixtures.CLIENT_ONE, 0, "concurrent one")
                    },
                    async(Dispatchers.Default, start = CoroutineStart.LAZY) {
                        store.push(alice, TranscriptionFixtures.CLIENT_TWO, 0, "concurrent two")
                    },
                )
                jobs.forEach { it.start() }
                jobs.awaitAll()
            }
        }
        // awaitAll rethrows a failure from either push, so a push that threw fails the test here.
        assertEquals(
            "sync-api: both concurrent pushes return a result",
            2,
            results.size,
        )
        assertEquals(
            "sync-api: both concurrent pushes store their rows",
            2,
            results.count { result -> result == PushResult.Stored },
        )

        val n1 = changeNo(temp, alice, TranscriptionFixtures.CLIENT_ONE)
        val n2 = changeNo(temp, alice, TranscriptionFixtures.CLIENT_TWO)
        assertEquals(
            "sync-api: two different numbers",
            2,
            setOf(n1, n2).size,
        )
        assertEquals(
            "sync-api: the two numbers are exactly {1, 2}",
            setOf(1L, 2L),
            setOf(n1, n2),
        )
        assertEquals(
            "sync-api: change_seq matches the highest number",
            2L,
            changeSeq(temp, alice),
        )
    }

    private fun changeNo(temp: dev.breaker.server.syncapi.db.TempDatabase, accountId: Long, clientId: String): Long =
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "SELECT change_no FROM transcriptions WHERE account_id = ? AND client_id = ?",
                ).use { s ->
                    s.setLong(1, accountId)
                    s.setString(2, clientId)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no row for $clientId" }
                        rows.getLong(1)
                    }
                }
            }
        }

    private fun changeSeq(temp: dev.breaker.server.syncapi.db.TempDatabase, accountId: Long): Long =
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement("SELECT change_seq FROM accounts WHERE id = ?").use { s ->
                    s.setLong(1, accountId)
                    s.executeQuery().use { rows ->
                        check(rows.next()) { "no account" }
                        rows.getLong(1)
                    }
                }
            }
        }
}
