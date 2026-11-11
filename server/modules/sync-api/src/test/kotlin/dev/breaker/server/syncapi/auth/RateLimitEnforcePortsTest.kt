package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.RateLimitResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The rule of [enforceLimits] played through its ports: every failure fails open, a cancellation never. */
internal class RateLimitEnforcePortsTest {

    private val refusal = RateLimitResult.Refused(30)

    private fun enforce(
        switchOn: suspend () -> Boolean,
        check: suspend (String) -> RateLimitResult,
    ): RateLimitResult.Refused? = runBlocking { enforceLimits(switchOn, { "peer" }, check) }

    private fun outcome(block: suspend () -> RateLimitResult.Refused?): String = runBlocking {
        try {
            block()
            "returned"
        } catch (stopped: CancellationException) {
            "cancelled"
        }
    }

    @Test
    fun `a switch read that fails lets the request through and never touches the counter`() {
        val touched = AtomicInteger(0)
        val result = enforce({ throw IllegalStateException("switch store down") }) {
            touched.incrementAndGet()
            refusal
        }
        assertNull("sync-api: a failed switch read is no limit", result)
        assertEquals("sync-api: the counter store is never touched", 0, touched.get())
    }

    @Test
    fun `a switch that is off lets the request through and never touches the counter`() {
        val touched = AtomicInteger(0)
        val result = enforce({ false }) {
            touched.incrementAndGet()
            refusal
        }
        assertNull("sync-api: switch off is no limit", result)
        assertEquals("sync-api: the counter store is never touched", 0, touched.get())
    }

    @Test
    fun `a counter that fails lets the request through`() {
        assertNull("sync-api: a failed counter is no limit", enforce({ true }) { throw IllegalStateException("counter down") })
    }

    @Test
    fun `a refusal from the counter comes back unchanged and an allowed one is no refusal`() {
        assertSame("sync-api: the refusal itself", refusal, enforce({ true }) { refusal })
        assertNull("sync-api: allowed", enforce({ true }) { RateLimitResult.Allowed })
    }

    @Test
    fun `the counter is asked with the address the address port gave`() {
        val asked = ArrayList<String>()
        runBlocking {
            enforceLimits({ true }, { "203.0.113.9" }) { address ->
                asked.add(address)
                RateLimitResult.Allowed
            }
        }
        assertEquals("sync-api: one check with that address", listOf("203.0.113.9"), asked)
    }

    @Test
    fun `a cancellation from the switch read propagates`() {
        assertEquals(
            "sync-api: cancellation is not swallowed into no limit",
            "cancelled",
            outcome { enforceLimits({ throw CancellationException("stop") }, { "peer" }, { refusal }) },
        )
    }

    @Test
    fun `a cancellation from the counter check propagates`() {
        assertEquals(
            "sync-api: cancellation is not swallowed into no limit",
            "cancelled",
            outcome { enforceLimits({ true }, { "peer" }, { throw CancellationException("stop") }) },
        )
    }
}
