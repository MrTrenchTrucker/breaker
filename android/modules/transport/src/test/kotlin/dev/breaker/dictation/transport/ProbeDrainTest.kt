package dev.breaker.dictation.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The drain helper's own test: a helper nobody tested is a copy that can rot.
 *
 * The answer function is the seam - the default builds a fresh probe; each test
 * injects its own so every behaviour can be forced without parking real workers.
 */
class ProbeDrainTest {

    @Test
    fun `the drain returns at once when the first fresh probe answers`() {
        var calls = 0
        drainProbeExecutor(
            hostLabel = "probe-drain-test",
            boundMs = 500,
            failureMessage = "the drain did not return when the first probe answered",
        ) { _ ->
            calls++
            true
        }
        assertEquals("the drain should answer on the first attempt and stop", 1, calls)
    }

    @Test
    fun `the drain keeps trying and returns when a later attempt answers`() {
        var calls = 0
        drainProbeExecutor(
            hostLabel = "probe-drain-test",
            boundMs = 500,
            failureMessage = "the drain did not return when a later probe answered",
        ) { _ ->
            calls++
            calls > 1
        }
        assertTrue("the drain should keep trying after a failed first attempt", calls > 1)
    }

    @Test
    fun `the drain throws AssertionError naming the bound and attempt count`() {
        try {
            drainProbeExecutor(
                hostLabel = "probe-drain-test",
                boundMs = 50,
                failureMessage = "the pool never answered",
            ) { _ -> false }
            throw AssertionError("the drain should have thrown")
        } catch (e: AssertionError) {
            val msg = e.message ?: ""
            assertTrue("message should name the bound (50)", msg.contains("50"))
            assertTrue("message should name attempts", msg.contains("attempts"))
            val counted = Regex("attempts [0-9]+").containsMatchIn(msg)
            assertTrue("message should contain a number", counted)
        }
    }

    @Test
    fun `the drain uses a different host on every attempt`() {
        val hosts = mutableListOf<String>()
        var answered = false
        drainProbeExecutor(
            hostLabel = "probe-drain-test",
            boundMs = 500,
            failureMessage = "the drain did not return when a later probe answered",
        ) { host ->
            hosts.add(host)
            if (!answered && hosts.size >= 3) { answered = true; true } else false
        }
        assertTrue("the drain should return when a later attempt answers", answered)
        assertTrue("the drain should try more than one host", hosts.size > 1)
        assertEquals("each attempt should use a different host", hosts.size, hosts.toSet().size)
    }
}
