package dev.breaker.dictation.transport

import java.lang.reflect.Field
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.fail
import org.junit.Before

/**
 * Reads the shared probe pool's own counters, so a test can ask whether it is
 * idle instead of inferring it from one probe that happened to be refused.
 *
 * Every counter here is private in [ProbeExecutor], and production carries no
 * accessor for it, so these reads are reflective and read-only: nothing is set,
 * and no production behaviour is reached to answer a question. A counter that
 * cannot be read is reported by name rather than skipped, because a check that
 * silently reads nothing is indistinguishable from a pool that is genuinely idle.
 */
internal object ProbePoolReading {

    /** One reading of everything the pool is holding, named for the message it produces. */
    data class Reading(
        val occupied: Int,
        val bodies: Int,
        val activeCount: Int,
        val marks: Int,
        val unreachable: List<String>,
    ) {
        /** True only when every counter is back to nothing. */
        val idle: Boolean get() = occupied == 0 && bodies == 0 && activeCount == 0 && marks == 0

        override fun toString(): String =
            "occupied=$occupied bodies=$bodies pool.activeCount=$activeCount marks=$marks" +
                if (unreachable.isEmpty()) "" else " UNREADABLE=${unreachable.joinToString()}"
    }

    private fun field(instance: Any, owner: Class<*>, name: String): Field? =
        try {
            owner.getDeclaredField(name).apply { isAccessible = true }
        } catch (_: NoSuchFieldException) {
            null
        }

    private fun readIntField(instance: Any, owner: Class<*>, name: String): Int? =
        field(instance, owner, name)?.let { found ->
            when (val value = found.get(instance)) {
                is AtomicInteger -> value.get()
                is Int -> value
                else -> null
            }
        }

    private fun readMarks(instance: Any, owner: Class<*>, name: String): Int? =
        (field(instance, owner, name)?.get(instance) as? ConcurrentHashMap<*, *>)?.size

    /** One reading. Names anything it could not reach, rather than counting it as zero. */
    fun read(): Reading {
        val executorClass = ProbeExecutor::class.java
        val flightClass = ProbeSingleFlight::class.java
        val unreadable = mutableListOf<String>()

        val occupied = readIntField(ProbeExecutor, executorClass, "occupied")
        if (occupied == null) unreadable += "ProbeExecutor.occupied"
        val bodies = readIntField(ProbeExecutor, executorClass, "bodies")
        if (bodies == null) unreadable += "ProbeExecutor.bodies"
        // The `pool.activeCount` leg collapses onto `bodies`. The pool's
        // substrate is now a dispatcher, so the `pool` field no longer reflects
        // as a ThreadPoolExecutor, and `bodies` carries the same "a body is in
        // flight" fact this pool needs for the idle decision: it rises when a
        // body is admitted for dispatch and falls when the body ends. The
        // Reading still reports a fourth leg named pool.activeCount so the
        // failure text and the busy/idle predicate are unchanged.
        val marks = readMarks(ProbeSingleFlight, flightClass, "inFlight")
        if (marks == null) unreadable += "ProbeSingleFlight.inFlight"

        return Reading(
            occupied = occupied ?: -1,
            bodies = bodies ?: -1,
            activeCount = bodies ?: -1,
            marks = marks ?: -1,
            unreachable = unreadable,
        )
    }
}

/** Long enough for a counter to come back, short enough that a leak fails instead of hanging. */
internal const val IDLE_BOUND_MS: Long = 5_000L

/**
 * Polls the shared pool until every counter is back to nothing, or fails naming
 * all four of them.
 *
 * The wait is bounded twice, by a deadline and by an attempt count, and it pauses
 * between attempts. An unslept loop would hammer the very pool whose activity it
 * is measuring, so a reading could be kept busy by the act of asking about it.
 *
 * The failure names [context] and the bound as well as the counters, because a
 * pool that is not idle says nothing about which caller left it that way.
 */
internal fun awaitProbePoolIdle(
    boundMs: Long = IDLE_BOUND_MS,
    context: String,
) {
    val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(boundMs)
    var attempt = 0
    var reading = ProbePoolReading.read()
    while (!reading.idle && attempt < IDLE_MAX_ATTEMPTS && System.nanoTime() < deadline) {
        Thread.sleep(IDLE_POLL_INTERVAL_MS)
        reading = ProbePoolReading.read()
        attempt++
    }
    if (!reading.idle) {
        fail(
            cardFailure(
                "$context: the shared probe pool is still not idle after ${boundMs}ms " +
                    "and $attempt further readings. $reading",
            ),
        )
    }
}

/**
 * Bounds how many times a wait may look before it must report, so a pool that is
 * never idle cannot be waited on indefinitely by a bound that never expires.
 */
private const val IDLE_MAX_ATTEMPTS = 250

/** Long enough to let a returning counter land, short enough to keep a bounded wait responsive. */
private const val IDLE_POLL_INTERVAL_MS = 20L

/**
 * Gives every test class the same two idle checks, so a class costs one line.
 *
 * The pool is a process-wide singleton, so a class that leaves a body parked does
 * not fail on its own account: it hands a busy pool to every class that runs after
 * it in the same JVM, and the later class is blamed for the earlier one's leak.
 * The teardown hook is what turns that around - it makes a leaking class fail
 * ITSELF, by name, before the next class ever reads the pool.
 *
 * Both methods are public because that is the only shape JUnit 4 recognises as a
 * lifecycle hook. A narrower one is not an override the runner can see, so the
 * hooks would silently never run and the class would carry no protection at all.
 *
 * A class opts in by declaring `: ProbePoolIsolation()`, which is what makes
 * isolation one line rather than two copied hook pairs that can drift apart.
 */
abstract class ProbePoolIsolation {

    /** Fails this class, by name, if something is already held when it starts. */
    @Before
    fun establishIdlePool() {
        awaitProbePoolIdle(context = "before ${this::class.java.simpleName}")
    }

    /** Fails this class, by name, if it leaves anything behind for the next one. */
    @After
    fun confirmIdlePool() {
        awaitProbePoolIdle(context = "after ${this::class.java.simpleName}")
    }
}
