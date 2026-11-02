package dev.breaker.server.syncapi.accounts

import dev.breaker.server.syncapi.db.TempDatabase
import dev.breaker.server.syncapi.db.TempDatabaseTest
import dev.breaker.server.syncapi.tokens.MutableClock
import java.time.Duration
import java.time.Instant
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The attempt sweep: a pass at once, a pass after every pause, a failed pass never ends the loop. */
internal class SigninAttemptSweepTest : TempDatabaseTest() {

    private val nowMs = AccountFixtures.FIXED_MILLIS
    private lateinit var temp: TempDatabase
    private lateinit var store: SigninAttemptStore

    @Before
    fun setUp() {
        temp = openTemp()
        store = SigninAttemptStore(temp.db, MutableClock(Instant.ofEpochMilli(nowMs)))
    }

    private suspend fun insertStaleRow(folded: String) {
        temp.db.transaction { connection ->
            connection.prepareStatement(
                "INSERT INTO signin_attempts (username_lower, wrong_count, locked_until_ms, last_attempt_ms) VALUES (?, 3, 0, ?)",
            ).use { statement ->
                statement.setString(1, folded)
                statement.setLong(2, nowMs - 2_000_000L)
                statement.executeUpdate()
            }
        }
    }

    private fun attemptOf(folded: String): SigninAttemptStore.AttemptInfo? =
        runBlocking { store.attemptOf(folded) }

    // Runs the loop until the pause ends it; [pause] gets the one-based call number.
    private fun runLoop(pause: suspend (Int, Duration) -> Unit): List<Duration> {
        val given = ArrayList<Duration>()
        runBlocking {
            val child = launch {
                runSigninAttemptSweep(
                    store,
                    pause = { interval ->
                        given.add(interval)
                        pause(given.size, interval)
                    },
                )
            }
            child.join()
        }
        return given
    }

    @Test
    fun `the interval is five minutes`() {
        assertEquals("sweep interval", Duration.ofMillis(300_000L), SIGNIN_SWEEP_INTERVAL)
    }

    @Test
    fun `the first pass runs before the first pause and the pause gets the interval`() {
        runBlocking { insertStaleRow("alice") }

        val given = runLoop { _, _ -> throw CancellationException("stop") }

        assertNull("the stale row was deleted before the pause", attemptOf("alice"))
        assertEquals("one pause", 1, given.size)
        assertEquals("the interval is passed on", Duration.ofMillis(300_000L), given[0])
    }

    @Test
    fun `a pass runs after every pause`() {
        val given = runLoop { call, _ ->
            if (call == 1) insertStaleRow("bob")
            if (call == 2) throw CancellationException("stop")
        }

        assertEquals("two pauses", 2, given.size)
        assertNull("the row made during the first pause was deleted by the second pass", attemptOf("bob"))
    }

    @Test
    fun `a failing pass over a closed database is swallowed and the loop goes on`() {
        temp.closeKeepingFiles()

        val given = runLoop { call, _ ->
            if (call == 2) throw CancellationException("stop")
        }

        assertEquals("the loop reached its second pause after a failed pass", 2, given.size)
    }

    @Test
    fun `a cancellation raised by a pass ends the loop`() {
        // The pause cancels the loop's own job and returns. The next pass starts a database call, which
        // raises the cancellation. If the loop swallowed it, the pause would be called again.
        val given = runLoop { call, _ ->
            check(call < 4) { "the loop kept going after its job was cancelled" }
            if (call == 1) coroutineContext[Job]!!.cancel()
        }

        assertEquals("one pause only", 1, given.size)
    }

    @Test
    fun `a live row is not deleted by a pass`() {
        runBlocking {
            temp.db.transaction { connection ->
                connection.prepareStatement(
                    "INSERT INTO signin_attempts (username_lower, wrong_count, locked_until_ms, last_attempt_ms) VALUES ('carol', 5, ?, ?)",
                ).use { statement ->
                    statement.setLong(1, nowMs + 300_000L)
                    statement.setLong(2, nowMs - 600_000L)
                    statement.executeUpdate()
                }
            }
        }

        runLoop { _, _ -> throw CancellationException("stop") }

        assertNotNull("the locked row stays", attemptOf("carol"))
    }
}
