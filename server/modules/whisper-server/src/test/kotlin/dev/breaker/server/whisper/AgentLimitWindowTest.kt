package dev.breaker.server.whisper

import dev.breaker.server.whisper.http.AgentRateLimit
import dev.breaker.server.whisper.http.LimitResult
import dev.breaker.server.whisper.jobs.MutableClock
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AgentLimitWindowTest {

    private val start = Instant.ofEpochSecond(1_700_000_000)
    private val clock = MutableClock(start)

    @Test
    fun `up to cap requests in the window are allowed then the next is refused`() {
        val limiter = AgentRateLimit(3, clock)
        repeat(3) {
            assertSame("request under cap is allowed", LimitResult.Allowed, limiter.allow("tok-a"))
        }
        assertTrue("request over cap is refused", limiter.allow("tok-a") is LimitResult.Refused)
    }

    @Test
    fun `after 60 000 ms the next request opens a new window with count 1`() {
        val limiter = AgentRateLimit(2, clock)
        limiter.allow("tok-a") // count 1
        limiter.allow("tok-a") // count 2 = cap
        assertTrue("over cap is refused", limiter.allow("tok-a") is LimitResult.Refused)
        clock.advance(Duration.ofMinutes(1))
        assertSame("new window allows count 1", LimitResult.Allowed, limiter.allow("tok-a"))
    }

    @Test
    fun `a refused request does not move the window start`() {
        val limiter = AgentRateLimit(1, clock)
        limiter.allow("tok-a") // count 1, windowStart = t0
        assertTrue("second is refused", limiter.allow("tok-a") is LimitResult.Refused)
        val third = limiter.allow("tok-a")
        assertTrue("third is still refused at the same instant", third is LimitResult.Refused)
        clock.advance(Duration.ofMinutes(1))
        assertSame("after a minute the window resets", LimitResult.Allowed, limiter.allow("tok-a"))
    }
}
