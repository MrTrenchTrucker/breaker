package dev.breaker.server.whisper

import dev.breaker.server.whisper.config.WhisperConfig
import dev.breaker.server.whisper.db.TempDatabaseTest
import dev.breaker.server.whisper.jobs.JobFixtures
import dev.breaker.server.whisper.jobs.JobPoll
import dev.breaker.server.whisper.jobs.MutableClock
import java.time.Duration
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/** main() builds the store through buildJobStore, which must thread the configured result TTL into
 *  the store. A config of 120 s makes the fetch window 120 s, not the 600 s default; proven at the
 *  boundary on both sides, so a buildJobStore that dropped the config (kept the default) goes red. */
internal class BuildJobStoreTest : TempDatabaseTest() {

    private val ttlSeconds = 120

    private fun textOf(poll: JobPoll): String {
        if (poll !is JobPoll.Result) {
            throw AssertionError("whisper-server: expected a result but got ${poll::class.simpleName}")
        }
        return poll.text
    }

    @Test
    fun `buildJobStore wires the configured ttl so the fetch window is 120 s not the default`() {
        val temp = openTemp()
        val clock = MutableClock(JobFixtures.START)
        val config = WhisperConfig.fromEnv(
            mapOf(
                "BREAKER_WHISPER_PORT" to "8080",
                "BREAKER_WHISPER_DB" to "/tmp/test.db",
                "BREAKER_WHISPER_FORWARDER_URL" to "http://localhost:9000",
                "BREAKER_WHISPER_RESULT_TTL_S" to ttlSeconds.toString(),
            ),
        )
        val store = buildJobStore(temp.db, config, clock)
        val id: Long = runBlocking { JobFixtures.doneJob(store, 1L, "the configured window") }

        clock.advance(Duration.ofMillis(ttlSeconds * 1000L - 1L))
        val within = runBlocking { store.fetch(id, 1L) }
        assertEquals(
            "whisper-server: one ms before the configured ttl the fetch must still return the text",
            "the configured window",
            textOf(within),
        )

        clock.advance(Duration.ofMillis(1L))
        val gone = runBlocking { store.fetch(id, 1L) }
        assertSame(
            "whisper-server: at exactly the configured ttl the fetch must answer result gone",
            JobPoll.ResultGone,
            gone,
        )
    }
}
