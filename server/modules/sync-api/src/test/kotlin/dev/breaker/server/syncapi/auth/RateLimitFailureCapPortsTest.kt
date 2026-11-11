package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.RateLimitResult
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

/** The rule of [enforceFailureCap] played through its ports: peek, then count, and every failure fails open. */
internal class RateLimitFailureCapPortsTest {

    private val refusal = RateLimitResult.Refused(30)
    private val peeked = ArrayList<String>()
    private val counted = ArrayList<String>()

    private fun enforce(
        switchOn: suspend () -> Boolean = { true },
        peekOver: suspend (String) -> RateLimitResult.Refused? = { null },
        countFailure: suspend (String) -> Unit = { },
    ): RateLimitResult.Refused? = runBlocking {
        enforceFailureCap(
            switchOn,
            { "203.0.113.5" },
            { address -> peeked.add(address); peekOver(address) },
            { address -> countFailure(address); counted.add(address) },
        )
    }

    private fun outcome(block: suspend () -> RateLimitResult.Refused?): String = runBlocking {
        try {
            block()
            "returned"
        } catch (stopped: CancellationException) {
            "cancelled"
        }
    }

    @Test
    fun `under the cap the failure is counted once for the address and the request goes on`() {
        assertNull("sync-api: no refusal", enforce())
        assertEquals("sync-api: peeked with the address", listOf("203.0.113.5"), peeked)
        assertEquals("sync-api: counted once for the address", listOf("203.0.113.5"), counted)
    }

    @Test
    fun `over the cap the refusal comes back and nothing is counted`() {
        assertSame("sync-api: the refusal itself", refusal, enforce(peekOver = { refusal }))
        assertEquals("sync-api: nothing counted", 0, counted.size)
    }

    @Test
    fun `a switch that is off peeks nothing and counts nothing`() {
        assertNull("sync-api: off is no limit", enforce(switchOn = { false }, peekOver = { refusal }))
        assertEquals("sync-api: no peek", 0, peeked.size)
        assertEquals("sync-api: no count", 0, counted.size)
    }

    @Test
    fun `a switch read that fails lets the request through and never touches the counter`() {
        val touched = AtomicInteger(0)
        val result = enforce(
            switchOn = { throw IllegalStateException("switch store down") },
            peekOver = { touched.incrementAndGet(); refusal },
            countFailure = { touched.incrementAndGet() },
        )
        assertNull("sync-api: a failed switch read is no limit", result)
        assertEquals("sync-api: the counter store is never touched", 0, touched.get())
        assertEquals("sync-api: no peek", 0, peeked.size)
        assertEquals("sync-api: no count", 0, counted.size)
    }

    @Test
    fun `a failing cap check lets the request through and the failure is still counted`() {
        assertNull("sync-api: no refusal", enforce(peekOver = { throw IllegalStateException("counter down") }))
        assertEquals("sync-api: counted", 1, counted.size)
    }

    @Test
    fun `a failing count lets the request through`() {
        assertNull("sync-api: no refusal", enforce(countFailure = { throw IllegalStateException("counter down") }))
    }

    @Test
    fun `a cancellation from the switch read propagates`() {
        assertEquals("cancelled", outcome { enforceFailureCap({ throw CancellationException("stop") }, { "a" }, { null }, { }) })
    }

    @Test
    fun `a cancellation from the cap check propagates`() {
        assertEquals("cancelled", outcome { enforceFailureCap({ true }, { "a" }, { throw CancellationException("stop") }, { }) })
    }

    @Test
    fun `a cancellation from the count propagates`() {
        assertEquals("cancelled", outcome { enforceFailureCap({ true }, { "a" }, { null }, { throw CancellationException("stop") }) })
    }
}
