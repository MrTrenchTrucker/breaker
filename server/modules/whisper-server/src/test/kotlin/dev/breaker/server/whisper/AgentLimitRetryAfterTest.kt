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

internal class AgentLimitRetryAfterTest {

    private val start = Instant.ofEpochSecond(1_700_000_000)
    private val clock = MutableClock(start)

    @Test
    fun `retry after is the time left as a whole second`() {
        val limiter = AgentRateLimit(1, clock)
        limiter.allow("tok-a") // windowStart = t0, count 1
        clock.advance(Duration.ofSeconds(1)) // now = t0 + 1 s, 59 s left
        val refused = limiter.allow("tok-a")
        assertTrue("over cap is refused", refused is LimitResult.Refused)
        val retryAfter = (refused as LimitResult.Refused).retryAfterSeconds
        assertEquals("retry after is 59 seconds as a whole second", 59, retryAfter)
    }

    @Test
    fun `retry after is at least 1 even one millisecond before the window ends`() {
        val limiter = AgentRateLimit(1, clock)
        limiter.allow("tok-a")
        clock.advance(Duration.ofMinutes(1).minus(Duration.ofMillis(1))) // now = t0 + 59 999 ms
        val refused = limiter.allow("tok-a")
        assertTrue("over cap is refused", refused is LimitResult.Refused)
        assertEquals("retry after is at least 1", 1, (refused as LimitResult.Refused).retryAfterSeconds)
    }
}
