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

internal class AgentLimitOffByDefaultTest {

    private val start = Instant.ofEpochSecond(1_700_000_000)
    private val clock = MutableClock(start)

    @Test
    fun `a cap of ten thousand allows ten thousand requests and refuses the next`() {
        val limiter = AgentRateLimit(10_000, clock)
        repeat(10_000) {
            val every = limiter.allow("tok-a")
            assertSame("every request must be allowed while under the cap", LimitResult.Allowed, every)
        }
        assertTrue("the next request is over the cap", limiter.allow("tok-a") is LimitResult.Refused)
    }

    @Test
    fun `a cap of one allows the first and refuses the second`() {
        val limiter = AgentRateLimit(1, clock)
        val first = limiter.allow("tok-a")
        assertTrue("first request is allowed", first is LimitResult.Allowed)
        val second = limiter.allow("tok-a")
        assertTrue("second request over cap 1 is refused", second is LimitResult.Refused)
        assertEquals("the whole minute is left", 60, (second as LimitResult.Refused).retryAfterSeconds)
    }

    @Test
    fun `each token has its own counter`() {
        val limiter = AgentRateLimit(1, clock)
        assertTrue("token a first is allowed", limiter.allow("tok-a") is LimitResult.Allowed)
        assertTrue("token b first is allowed", limiter.allow("tok-b") is LimitResult.Allowed)
        assertTrue("token a second is refused", limiter.allow("tok-a") is LimitResult.Refused)
        assertTrue("token b second is refused", limiter.allow("tok-b") is LimitResult.Refused)
    }
}
