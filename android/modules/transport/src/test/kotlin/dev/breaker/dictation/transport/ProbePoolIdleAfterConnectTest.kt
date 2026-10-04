package dev.breaker.dictation.transport

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reads the shared pool straight after the connect-side test has run, with both
 * halves in ONE test method so the order cannot be ambiguous.
 *
 * A suite declares an order, but two classes' results are written as each
 * finishes, and on this run the two files carried the same timestamp to the
 * nanosecond - so a suite could not show which half ran first. One method cannot
 * be ambiguous about that. The connect-side test's own method is called directly
 * rather than through a runner, which is the only reason the order here is
 * guaranteed rather than requested.
 *
 * The reading is reflective because every counter in [ProbeExecutor] is private
 * and production carries no accessor. It only reads; it writes nothing and
 * reaches no production behaviour to answer the question.
 *
 * The base class is declared for the one-line reason it exists: the pool is
 * shared across the whole JVM, so this class checks on both sides of its own
 * tests that it handed the next class an idle pool, and got an idle pool itself.
 */
class ProbePoolIdleAfterConnectTest : ProbePoolIsolation() {

    /**
     * Runs the connect-side test in this process, then asks whether anything is
     * still held.
     *
     * A counter returned late is timing. A counter never returned outlives the
     * next few probes. This separates the two rather than reporting one number
     * that could mean either.
     */
    @Test
    fun `the pool is idle immediately after a connect that threw`() {
        TcpConnectivityProbeThrowingConnectTest()
            .`a connect that throws answers not reachable and leaves the pool serving`()

        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(IDLE_BOUND_MS)
        var reading = ProbePoolReading.read()
        val elapsedOnFirstRead = System.nanoTime()
        while (!reading.idle && System.nanoTime() < deadline) {
            Thread.sleep(POLL_INTERVAL_MS)
            reading = ProbePoolReading.read()
        }

        assertEquals(
            cardFailure("the counters this check depends on must all be readable; $reading"),
            listOf<String>(),
            reading.unreachable,
        )
        assertTrue(
            cardFailure(
                "a connect that threw got answered before this reading began, and the pool still holds: $reading. " +
                    "Counters returned late are timing; counters never returned outlive the process",
            ),
            reading.idle,
        )
        // Reported so the two cases can be told apart without re-running: a first
        // read that is already idle is a counter returned promptly, not one that
        // happened to recover inside the bound.
        println(
            "POOL READING AFTER THROWING CONNECT: $reading  " +
                "firstReadAlreadyIdle=${!reading.idle || System.nanoTime() - elapsedOnFirstRead < POLL_INTERVAL_MS}",
        )
    }

    /**
     * The reading must be able to report busy, or "idle" means nothing at all.
     *
     * A check that cannot fail is not a check. This parks one body and requires
     * the same reading to call the pool busy, and then releases it.
     */
    @Test
    fun `the same reading reports busy while one body is parked`() {
        val gate = CountDownLatch(1)
        val parked = ProbeExecutor.execute { gate.await(PARKED_AWAIT_MS, TimeUnit.MILLISECONDS) }
        assertTrue(
            cardFailure("a gated body must be admitted before the reading that proves the reading can see it"),
            parked,
        )

        val reading = ProbePoolReading.read()
        assertEquals(
            cardFailure("the counters this check depends on must all be readable; $reading"),
            listOf<String>(),
            reading.unreachable,
        )
        assertTrue(
            cardFailure("the pool is holding a body that never got released, yet the reading says idle: $reading"),
            !reading.idle,
        )
        println("POOL READING WITH ONE BODY PARKED: $reading")

        gate.countDown()
    }

    /**
     * Requires the idle wait itself to fail while something is held, and to say
     * which four values it saw.
     *
     * Without this, "idle" and "blind" produce the same green result, and the
     * hooks inherited from [ProbePoolIsolation] would guard the suite with a
     * check that cannot fail. The short bound keeps the deliberate failure quick;
     * the body is released whatever happens, so the teardown hook sees an idle
     * pool and this class reports only the failure it meant to provoke.
     */
    @Test
    fun `the idle wait reports a failure naming all four counters while a body is parked`() {
        val gate = CountDownLatch(1)
        val parked = ProbeExecutor.execute { gate.await(PARKED_AWAIT_MS, TimeUnit.MILLISECONDS) }
        assertTrue(
            cardFailure("a gated body must be admitted before the wait that proves the wait can fail"),
            parked,
        )

        val reported: String? = try {
            awaitProbePoolIdle(boundMs = FIRE_BOUND_MS, context = "firing control")
            null
        } catch (expected: AssertionError) {
            expected.message
        } finally {
            gate.countDown()
        }
        assertTrue(
            cardFailure("a body stays parked for the whole bound, yet the idle wait reports success"),
            reported != null,
        )
        for (counter in ALL_COUNTER_NAMES) {
            assertTrue(
                cardFailure("the idle wait's failure must name $counter by name so it is diagnosable; got: $reported"),
                reported.orEmpty().contains(counter),
            )
        }
        println("IDLE WAIT FAILURE WHILE BUSY: $reported")
    }

    private companion object {
        const val IDLE_BOUND_MS = 5_000L
        const val PARKED_AWAIT_MS = 30_000L
        const val POLL_INTERVAL_MS = 20L
        const val FIRE_BOUND_MS = 250L
        val ALL_COUNTER_NAMES = listOf("occupied", "bodies", "pool.activeCount", "marks")
    }
}
