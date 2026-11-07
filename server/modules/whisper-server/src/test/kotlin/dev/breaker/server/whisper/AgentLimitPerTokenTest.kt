package dev.breaker.server.whisper

import dev.breaker.server.whisper.http.AgentRateLimit
import dev.breaker.server.whisper.http.LimitResult
import dev.breaker.server.whisper.jobs.MutableClock
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AgentLimitPerTokenTest {

    private val start = Instant.ofEpochSecond(1_700_000_000)
    private val clock = MutableClock(start)

    @Test
    fun `two agent tokens have separate counters`() {
        val limiter = AgentRateLimit(1, clock)
        assertTrue("token a first is allowed", limiter.allow("tok-a") is LimitResult.Allowed)
        assertTrue("token b first is allowed", limiter.allow("tok-b") is LimitResult.Allowed)
        assertTrue("token a second is refused", limiter.allow("tok-a") is LimitResult.Refused)
        assertTrue("token b second is refused", limiter.allow("tok-b") is LimitResult.Refused)
    }

    @Test
    fun `one token post and get calls share one counter`() {
        val limiter = AgentRateLimit(2, clock)
        assertSame("post count 1 is allowed", LimitResult.Allowed, limiter.allow("tok-a"))
        assertSame("get count 2 is allowed", LimitResult.Allowed, limiter.allow("tok-a"))
        assertTrue("post count 3 over cap is refused", limiter.allow("tok-a") is LimitResult.Refused)
        assertTrue("get count 3 over cap is refused", limiter.allow("tok-a") is LimitResult.Refused)
    }
}
