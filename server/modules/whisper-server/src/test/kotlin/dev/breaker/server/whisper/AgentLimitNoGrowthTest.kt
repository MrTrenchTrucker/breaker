package dev.breaker.server.whisper

import dev.breaker.server.whisper.http.AgentRateLimit
import dev.breaker.server.whisper.http.LimitResult
import dev.breaker.server.whisper.jobs.MutableClock
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AgentLimitNoGrowthTest {

    private val start = Instant.ofEpochSecond(1_700_000_000)
    private val clock = MutableClock(start)

    @Test
    fun `windows older than a minute are dropped so the map cannot grow`() {
        val limiter = AgentRateLimit(1, clock)
        // One request from each of 100 distinct tokens, all in the same window.
        repeat(100) { index ->
            assertSame("token $index allowed", LimitResult.Allowed, limiter.allow("tok-$index"))
        }
        assertEquals("100 live windows tracked", 100, limiter.size())
        // Advance past a minute: every window is old. The next call drops them all.
        clock.advance(Duration.ofMinutes(1).plus(Duration.ofSeconds(1)))
        assertSame("fresh request allowed", LimitResult.Allowed, limiter.allow("tok-new"))
        assertEquals("one live window after a fresh request", 1, limiter.size())
    }
}
