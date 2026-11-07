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

internal class AgentLimit429BodyTest {

    private val start = Instant.ofEpochSecond(1_700_000_000)
    private val clock = MutableClock(start)

    private fun retryAfterOf(result: LimitResult): Int {
        assertTrue("the request must be refused", result is LimitResult.Refused)
        return (result as LimitResult.Refused).retryAfterSeconds
    }

    @Test
    fun `a refusal in the first instant says to retry after sixty seconds`() {
        val limiter = AgentRateLimit(1, clock)
        limiter.allow("tok-a")
        assertEquals("the whole minute is left", 60, retryAfterOf(limiter.allow("tok-a")))
    }

    @Test
    fun `the retry after shrinks as the window runs down and never drops below one`() {
        val limiter = AgentRateLimit(1, clock)
        limiter.allow("tok-a")
        clock.advance(Duration.ofSeconds(30))
        assertEquals("half the minute is left", 30, retryAfterOf(limiter.allow("tok-a")))
        clock.advance(Duration.ofSeconds(29).plusMillis(500))
        assertEquals("half a second left rounds up to one", 1, retryAfterOf(limiter.allow("tok-a")))
    }

    @Test
    fun `a request exactly one window after the first opens a new window`() {
        val limiter = AgentRateLimit(1, clock)
        limiter.allow("tok-a")
        clock.advance(Duration.ofMinutes(1))
        assertSame("the boundary opens a fresh window", LimitResult.Allowed, limiter.allow("tok-a"))
        assertEquals("and that window counts again", 60, retryAfterOf(limiter.allow("tok-a")))
    }

    @Test
    fun `a remainder of the second rounds the retry after up and a whole second does not`() {
        val limiter = AgentRateLimit(1, clock)
        limiter.allow("tok-a")
        clock.advance(Duration.ofMillis(500))
        assertEquals("59 500 ms left rounds up to 60", 60, retryAfterOf(limiter.allow("tok-a")))
        clock.advance(Duration.ofMillis(499))
        assertEquals("59 001 ms left rounds up to 60", 60, retryAfterOf(limiter.allow("tok-a")))
        clock.advance(Duration.ofMillis(1))
        assertEquals("59 000 ms left is exactly 59", 59, retryAfterOf(limiter.allow("tok-a")))
    }
}
