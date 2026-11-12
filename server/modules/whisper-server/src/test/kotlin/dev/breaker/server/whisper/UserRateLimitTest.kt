package dev.breaker.server.whisper

import dev.breaker.server.whisper.http.UserRateLimit
import dev.breaker.server.whisper.http.UserLimitResult
import dev.breaker.server.whisper.jobs.MutableClock
import java.time.Duration
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for UserRateLimit, mirroring AgentLimit*Test: keyed by accountId (Long),
 *  in-memory fixed window, 429 before the read, Retry-After ceil seconds. */
internal class UserRateLimitTest {

    private val start = Instant.ofEpochSecond(1_700_000_000)
    private val clock = MutableClock(start)

    @Test
    fun `up to cap requests in the window are allowed then the next is refused`() {
        val limiter = UserRateLimit(3, clock)
        repeat(3) {
            assertSame("request under cap is allowed", UserLimitResult.Allowed, limiter.allow(7L))
        }
        assertTrue("request over cap is refused", limiter.allow(7L) is UserLimitResult.Refused)
    }

    @Test
    fun `after 60 000 ms the next request opens a new window with count 1`() {
        val limiter = UserRateLimit(2, clock)
        limiter.allow(7L) // count 1
        limiter.allow(7L) // count 2 = cap
        assertTrue("over cap is refused", limiter.allow(7L) is UserLimitResult.Refused)
        clock.advance(Duration.ofMinutes(1))
        assertSame("new window allows count 1", UserLimitResult.Allowed, limiter.allow(7L))
    }

    @Test
    fun `a refused request does not move the window start`() {
        val limiter = UserRateLimit(1, clock)
        limiter.allow(7L) // count 1, windowStart = t0
        assertTrue("second is refused", limiter.allow(7L) is UserLimitResult.Refused)
        val third = limiter.allow(7L)
        assertTrue("third is still refused at the same instant", third is UserLimitResult.Refused)
        clock.advance(Duration.ofMinutes(1))
        assertSame("after a minute the window resets", UserLimitResult.Allowed, limiter.allow(7L))
    }

    @Test
    fun `each account has its own counter`() {
        val limiter = UserRateLimit(1, clock)
        assertTrue("account 7 first is allowed", limiter.allow(7L) is UserLimitResult.Allowed)
        assertTrue("account 8 first is allowed", limiter.allow(8L) is UserLimitResult.Allowed)
        assertTrue("account 7 second is refused", limiter.allow(7L) is UserLimitResult.Refused)
        assertTrue("account 8 second is refused", limiter.allow(8L) is UserLimitResult.Refused)
    }

    @Test
    fun `windows older than a minute are dropped so the map cannot grow`() {
        val limiter = UserRateLimit(1, clock)
        repeat(100) { index ->
            assertSame("account $index allowed", UserLimitResult.Allowed, limiter.allow(index.toLong()))
        }
        assertEquals("100 live windows tracked", 100, limiter.size())
        clock.advance(Duration.ofMinutes(1).plus(Duration.ofSeconds(1)))
        assertSame("fresh request allowed", UserLimitResult.Allowed, limiter.allow(999L))
        assertEquals("one live window after a fresh request", 1, limiter.size())
    }

    @Test
    fun `retry after is the time left as a whole second`() {
        val limiter = UserRateLimit(1, clock)
        limiter.allow(7L) // windowStart = t0, count 1
        clock.advance(Duration.ofSeconds(1)) // now = t0 + 1 s, 59 s left
        val refused = limiter.allow(7L)
        assertTrue("over cap is refused", refused is UserLimitResult.Refused)
        val retryAfter = (refused as UserLimitResult.Refused).retryAfterSeconds
        assertEquals("retry after is 59 seconds as a whole second", 59, retryAfter)
    }

    @Test
    fun `retry after is at least 1 even one millisecond before the window ends`() {
        val limiter = UserRateLimit(1, clock)
        limiter.allow(7L)
        clock.advance(Duration.ofMinutes(1).minus(Duration.ofMillis(1))) // now = t0 + 59 999 ms
        val refused = limiter.allow(7L)
        assertTrue("over cap is refused", refused is UserLimitResult.Refused)
        assertEquals("retry after is at least 1", 1, (refused as UserLimitResult.Refused).retryAfterSeconds)
    }

    @Test
    fun `retry after rounds a partial second up and a whole second stays`() {
        val limiter = UserRateLimit(1, clock)
        limiter.allow(7L)
        clock.advance(Duration.ofMillis(500))
        val partial = limiter.allow(7L)
        assertTrue("over cap is refused", partial is UserLimitResult.Refused)
        assertEquals("59 500 ms left rounds up to 60, a floor would say 59", 60, (partial as UserLimitResult.Refused).retryAfterSeconds)
        clock.advance(Duration.ofMillis(500))
        val whole = limiter.allow(7L)
        assertEquals("59 000 ms left is exactly 59", 59, (whole as UserLimitResult.Refused).retryAfterSeconds)
    }

    @Test
    fun `an allowed request does not move the window start`() {
        val limiter = UserRateLimit(2, clock)
        limiter.allow(7L) // window opens at t0, count 1
        clock.advance(Duration.ofSeconds(30))
        assertSame("the second request is inside the cap", UserLimitResult.Allowed, limiter.allow(7L))
        clock.advance(Duration.ofSeconds(30)) // now t0 + 60 s: the window that opened at t0 is over
        assertSame("a sliding window would still refuse here, a fixed one reopens", UserLimitResult.Allowed, limiter.allow(7L))
        assertSame("and the new window counts from this request", UserLimitResult.Allowed, limiter.allow(7L))
        assertTrue("the cap of two holds in the new window", limiter.allow(7L) is UserLimitResult.Refused)
    }
}
