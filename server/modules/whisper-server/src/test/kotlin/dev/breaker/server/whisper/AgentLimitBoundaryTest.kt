package dev.breaker.server.whisper

import dev.breaker.server.whisper.http.AgentRateLimit
import dev.breaker.server.whisper.http.LimitResult
import dev.breaker.server.whisper.jobs.MutableClock
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AgentLimitBoundaryTest {

    private val start = Instant.ofEpochSecond(1_700_000_000)
    private val clock = MutableClock(start)

    @Test
    fun `a request exactly at windowStart plus 60 000 ms opens a new window`() {
        val limiter = AgentRateLimit(2, clock)
        limiter.allow("tok-a") // windowStart = t0, count 1
        limiter.allow("tok-a") // count 2 = cap
        assertTrue("over cap is refused", limiter.allow("tok-a") is LimitResult.Refused)
        clock.advance(Duration.ofMinutes(1)) // now = t0 + 60 000 ms exactly
        assertSame("exactly at the boundary opens a new window", LimitResult.Allowed, limiter.allow("tok-a"))
    }

    @Test
    fun `a request one millisecond before the boundary is still refused`() {
        val limiter = AgentRateLimit(2, clock)
        limiter.allow("tok-a")
        limiter.allow("tok-a")
        clock.advance(Duration.ofMillis(59_999)) // now = t0 + 59 999 ms
        val boundary = limiter.allow("tok-a")
        assertTrue("one millisecond before the boundary is still refused", boundary is LimitResult.Refused)
    }
}
