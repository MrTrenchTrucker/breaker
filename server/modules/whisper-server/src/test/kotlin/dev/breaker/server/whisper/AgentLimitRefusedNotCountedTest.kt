package dev.breaker.server.whisper

import dev.breaker.server.whisper.http.AgentRateLimit
import dev.breaker.server.whisper.http.LimitResult
import dev.breaker.server.whisper.jobs.MutableClock
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Note for the report: no test can see whether a refused request is added to the count, because
 * the check is count < cap and the entry is replaced when the next window opens. This class
 * pins the window start (a refusal must not move it); the not-counted half is UNFALSIFIABLE.
 */
internal class AgentLimitRefusedNotCountedTest {

    private val start = Instant.ofEpochSecond(1_700_000_000)
    private val clock = MutableClock(start)

    @Test
    fun `refusals do not add to the count or move the window start`() {
        val limiter = AgentRateLimit(1, clock)
        limiter.allow("tok-a") // count 1, windowStart = t0
        assertTrue("first refusal at t0", limiter.allow("tok-a") is LimitResult.Refused)
        assertTrue("second refusal at t0", limiter.allow("tok-a") is LimitResult.Refused)
        // A full minute later, no matter how many refusals came before, a new window opens.
        clock.advance(Duration.ofMinutes(1))
        assertSame("new window after a minute despite refusals", LimitResult.Allowed, limiter.allow("tok-a"))
        assertTrue("refusal after the new window count 1", limiter.allow("tok-a") is LimitResult.Refused)
    }
}
