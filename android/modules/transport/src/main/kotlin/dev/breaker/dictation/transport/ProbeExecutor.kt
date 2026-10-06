package dev.breaker.dictation.transport

import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch

/**
 * The bounded pool that resolution-plus-connection bodies run on, and the
 * admission that keeps one wedged lookup from starving the process. The
 * design prose - why the pool is process-wide, why the slot and the thread
 * are two counters released at two different moments, why admission takes
 * both or neither, and why a body that throws is contained in the launch
 * block - is in the module card (android/modules/transport/AGENTS.md,
 * Threading, "The bounded pool's design"), kept there per topic like the
 * rest of the card's per-site reasons. The contract this type keeps:
 *
 * - [MAX_WEDGED_PROBES] (the slot) and [POOL_MAX_THREADS] (the thread, the
 *   slot cap plus one headroom thread - the pool's own `maximumPoolSize`)
 *   are the only limits, and at either one [execute] answers false at once,
 *   never running the task on the calling thread, so the caller answers
 *   "not reachable" without a connection attempt ever being made.
 * - [occupied] comes back at PUBLISH, by [answering]'s `finally`, before
 *   the value it produced can be observed; [bodies] comes back only when the
 *   body ENDS - it returns, or it throws - in the submitted wrapper's
 *   `finally`. The two moments differ on purpose.
 * - [claim] checks both counters under one lock and takes both or neither;
 *   a refused attempt writes nothing and holds no token to release.
 * - A body that throws is contained in the launch block: the launched
 *   coroutine completes normally, the wrapper's `finally` gives both
 *   counters back, and no thread's uncaught-exception handler is ever
 *   reached by a body's throw.
 * - The pool is process-wide, its workers are daemon threads, and it is
 *   never shut down.
 */
internal object ProbeExecutor {

    /**
     * The most probe tasks this pool will run at once, wedged ones included.
     *
     * This cap is a PROMISE this module makes and enforces, not a consequence
     * of how the pool happens to be sized; see [CAP_HEADROOM_THREADS].
     */
    const val MAX_WEDGED_PROBES: Int = 2

    /**
     * Threads the JDK pool is allowed beyond [MAX_WEDGED_PROBES].
     *
     * Exactly one, and it is load-bearing: it is what makes [claim] the only
     * refuser, so a task [claim] admits always has a worker and the pool's
     * rejection handler (which throws [java.util.concurrent.RejectedExecutionException],
     * caught and resubmitted by the dispatcher - see that handler's comment)
     * stays a backstop behind [claim] rather than the mechanism. Sized to the
     * cap instead, the JDK would refuse overflow work indistinguishably from a
     * cap refusal and a neutralised [claim] guard would be undetectable.
     */
    private const val CAP_HEADROOM_THREADS: Int = 1

    /**
     * The pool's `maximumPoolSize`, and the ceiling [bodies] is checked
     * against at admission. It is [MAX_WEDGED_PROBES] plus its headroom.
     *
     * **This is a check, not a sizing.** [bodies] counts bodies that have
     * started and not yet ended, so the thread bound and the pool's own ceiling
     * are the same number from two directions: the pool would refuse to grow
     * past it, and [claim] has already refused a body that would have. Because
     * [bodies] only ever rises for a body the pool accepted, [claim] admits on
     * `bodies < POOL_MAX_THREADS` exactly the tasks the pool can run.
     */
    private const val POOL_MAX_THREADS: Int = MAX_WEDGED_PROBES + CAP_HEADROOM_THREADS

    /**
     * How long a worker that has FINISHED a task waits for the next one before
     * retiring. Long enough that the ordinary case - a probe every few seconds
     * - reuses its workers; short enough that a burst cannot pin threads for
     * long after the traffic stops. It does not bound a wedged worker: that
     * one is inside the lookup and never reaches this window.
     */
    private const val WORKER_IDLE_MS = 30_000L

    /** Why an admission was refused, and what a caller is to conclude. */
    internal enum class Refusal(private val explanation: String) {
        /** Every slot of [MAX_WEDGED_PROBES] is held by a body that has not answered. */
        NO_FREE_SLOT("no free probe slot: every slot is held by a lookup that has not answered"),

        /** Every thread of [POOL_MAX_THREADS] is held by a body that has not ended. */
        NO_FREE_THREAD(
            "no free probe worker thread: thread saturation - every thread is held by a task body that has not ended",
        ),

        /** This host's own lookup is already in flight. */
        HOST_IN_FLIGHT("this host's lookup is already in flight");

        /**
         * What refused the task, in words. A caller that reports this is
         * reporting that NOTHING was measured.
         */
        fun reason(): String = explanation
    }

    /**
     * The one lock both admission checks are taken under; see [claim].
     *
     * **Why a monitor, not a `Mutex`.** The sites that take it - [claim],
     * [SlotRelease.release] and [BodyRelease.release] - are all non-suspend,
     * reached from [executeReporting] and [answering] and the submitted
     * wrapper's `finally`. `Mutex.withLock` takes a suspend lambda and cannot
     * be called from any of them, so the critical section stays a monitor:
     * the two counters are checked and written as one indivisible step, which
     * is the property a `Mutex` would not add.
     */
    private val admission = Any()

    /** Tasks admitted and not yet ANSWERED; the wedged count. */
    private val occupied = AtomicInteger(0)

    /**
     * Task bodies that have STARTED and not yet ENDED, answered or not; the
     * thread count. Ceilinged by [POOL_MAX_THREADS] at admission and given back
     * by the submitted wrapper's `finally`, when the body ends - NOT at publish.
     * See the object KDoc for why the two counters end at different moments.
     */
    private val bodies = AtomicInteger(0)

    /**
     * The [SlotRelease] of the admission this thread is currently running, or
     * null when this thread is not running an admitted body.
     *
     * **Why a thread-local and not a parameter.** [answering] is called from
     * INSIDE the body - that is what makes it run before the value escapes -
     * and its signature is fixed by [TcpConnectivityProbe] and by the tests
     * that pin the mark's lifetime. Threading a token through it would hand
     * every caller a value it has no use for, and a caller that dropped it
     * would silently reintroduce this defect. So the wrapper publishes its own
     * token here for the length of its body and [answering] reads it.
     *
     * Deliberately NOT [java.lang.InheritableThreadLocal]: inheriting hands a
     * child's thread the PARENT's slot, so an [answering] call there would
     * release a DIFFERENT admission's slot. With a plain [ThreadLocal] a body
     * that moves the work to another thread finds no token, releases nothing,
     * and the real wrapper's backstop still covers the admission. Finding no
     * token is always safe; finding the wrong one is not.
     *
     * [ThreadLocal.withInitial] with null, NOT the no-argument constructor: the
     * no-arg [ThreadLocal] calls `initialValue()` on an unset read and the JDK's
     * implementation THROWS there. `get()` must be safe on any thread not
     * running a body - which is exactly the case the reasoning above leans on
     * - so it has to return null rather than throw.
     *
     * **Why not a coroutine context element.** [answering] is non-suspend -
     * its signature is pinned by [TcpConnectivityProbe] and the tests - so it
     * cannot read `currentCoroutineContext()`; a context element would need a
     * `ThreadLocal` behind it to be reachable from non-suspend code anyway,
     * which is this. A plain [ThreadLocal] is the honest shape.
     */
    private val runningSlot: ThreadLocal<SlotRelease?> =
        ThreadLocal.withInitial<SlotRelease?> { null }

    /** Numbers the daemon threads so a wedged worker is identifiable in a dump. */
    private val started = AtomicInteger(0)

    /**
     * The bounded substrate the probe bodies run on, exposed to the module's
     * logic as a [CoroutineDispatcher].
     *
     * **Why the workers stay a hand-built pool.** A dispatcher
     * does not hand out a dedicated daemon thread at default priority: the
     * shared default pools recycle workers for unrelated work and would not
     * give this module a named, daemon, countable worker per admitted body. So
     * the workers are the pool's own daemon threads, started by the factory
     * below and numbered so a wedged one is identifiable in a dump - the same
     * threads as before the migration, now reached through a dispatcher rather
     * than handed a `Runnable` directly.
     *
     * [SynchronousQueue] is what makes the sizing matter at all: with it the
     * pool starts a thread per submitted task up to `maximumPoolSize`, so a
     * pool sized at exactly [MAX_WEDGED_PROBES] would let the pool enforce the
     * cap in place of [claim] - invisibly, and identically to the correct
     * refusal, which is to say not at all distinguishably.
     *
     * The thread substrate - the pool's sizing, its `SynchronousQueue`, its
     * daemon factory and its 30 s idle retirement - is UNCHANGED by the
     * migration; only the way a body is handed to it changed (a `launch` on the
     * [CoroutineDispatcher] instead of a direct `execute` of a `Runnable`).
     */
    private val pool: CoroutineDispatcher =
        ThreadPoolExecutor(
            0,
            POOL_MAX_THREADS,
            WORKER_IDLE_MS,
            TimeUnit.MILLISECONDS,
            // A direct handoff: an idle worker takes the task itself, and when
            // there is no idle worker a new one is started, up to the pool's
            // maximum. No queue, because a queued task is a task nobody is going
            // to run - its worker is the thing that is wedged. With no core
            // threads and a finite idle window, a worker that FINISHES a task is
            // offered the next one and only retires after [WORKER_IDLE_MS] of
            // quiet, so a steady stream of healthy probes reuses the same threads.
            SynchronousQueue(),
            { runnable ->
                Thread(runnable, "$PROBE_THREAD_NAME-${started.incrementAndGet()}").apply {
                    isDaemon = true
                }
            },
            { _, _ ->
                // The backstop behind [bodies]. UNREACHABLE while [claim] holds:
                // [claim] refuses a body once [bodies] reaches [POOL_MAX_THREADS],
                // which is the pool's own `maximumPoolSize`, so the pool has no
                // reason to refuse a task [claim] admitted. It is kept because
                // the accounting and the pool's thread count are two independent
                // mechanisms.
                //
                // What this does if it ever fires is NOT "stop an unbounded pile
                // of threads": the dispatcher wraps this pool
                // (asCoroutineDispatcher), and when execute() throws
                // RejectedExecutionException it catches it, CANCELS the launched
                // job, and resubmits the body onto Dispatchers.IO - which is not
                // bounded by this pool. So the containment that protects the
                // two counters in that case is NOT this throw; it is the launch
                // block's own catch + finally in [executeReporting] (started
                // ATOMIC, see the launch there), which runs where the body ends
                // up and returns both counters no matter which thread runs it.
                // This handler exists only to make a saturated hand-off a named,
                // catchable event instead of a silent drop.
                throw java.util.concurrent.RejectedExecutionException("probe worker pool is saturated")
            },
        ).asCoroutineDispatcher()

    /**
     * The scope that dispatches admitted bodies onto [pool]. A [SupervisorJob]
     * so one body that fails never cancels the scope and takes every later
     * probe with it.
     *
     * **There is deliberately no [CoroutineExceptionHandler] here.** The scope
     * has no parent to propagate a failure to, so the only reason to add one
     * would be to keep a body's failure off a worker thread's uncaught-exception
     * handler. That is not needed: [executeReporting] contains each body's throw
     * inside the launch block (see there), so a launched body - whether it is
     * the module's own [TcpConnectivityProbe] body or a raw external one -
     * ALWAYS completes its coroutine normally, and there is no exceptional
     * completion for a handler to catch. Adding one would be dead code that
     * implies a failure path the design has removed.
     */
    private val scope = CoroutineScope(SupervisorJob())

    /**
     * The once-only right to give back the one slot of [occupied] that one
     * admission holds.
     *
     * **Why a token at all, given there is only one count.** Two paths must be
     * able to release - [answering], for a body that answered, and the wrapper's
     * `finally`, for one that never did - and both belong to the SAME admission.
     * The hazard is real: a decrement that runs twice drives [occupied] below
     * zero, after which [claim]'s `occupied >= MAX_WEDGED_PROBES` guard is
     * satisfied by a negative number and the cap silently rises above its
     * promise, forever, with nothing to report it.
     *
     * **Why a double release is structurally impossible, not unlikely.**
     * [released] is a compare-and-set on a field only this object can reach,
     * and the decrement sits INSIDE the branch that wins it. So the second
     * caller - whichever path it is, in whatever order they race - observes
     * `released == true`, takes the other branch, and never touches [occupied].
     * No interleaving has both seeing `false`: CAS admits exactly one winner.
     * This is also what makes the wrapper's backstop SAFE to keep: it is
     * reachable where the answer already released the slot, and is a no-op
     * there rather than a second decrement.
     */
    private class SlotRelease {
        /** Flips exactly once, by exactly one caller; see the class KDoc. */
        private val released = AtomicBoolean(false)

        /**
         * Gives this admission's slot back, if it has not already been given
         * back. Never throws and never touches [occupied] twice.
         */
        fun release() {
            if (released.compareAndSet(false, true)) {
                synchronized(admission) { occupied.decrementAndGet() }
            }
        }
    }

    /**
     * The once-only right to give back the one thread of [bodies] that one
     * admission holds.
     *
     * The same hazard and the same shape as [SlotRelease] — a second decrement
     * would drive [bodies] below zero and lift [POOL_MAX_THREADS] off its
     * promise — but a DIFFERENT release moment, and that is the point. This is
     * called from the wrapper's `finally` and nowhere else: a body that has
     * published its answer still holds its thread until it ends.
     *
     * One token per [claim]: a refused admission has no token
     * and cannot release one.
     */
    private class BodyRelease {
        /** Flips exactly once, by exactly one caller; see the class KDoc. */
        private val released = AtomicBoolean(false)

        /** Gives this admission's thread back, if it has not already been. */
        fun release() {
            if (released.compareAndSet(false, true)) {
                synchronized(admission) { bodies.decrementAndGet() }
            }
        }
    }

    /**
     * Runs [task] on a daemon worker, or reports that there is nowhere to put
     * it.
     *
     * Never throws and never runs [task] on the calling thread: a `false`
     * return means "not reachable", answered at once. The caller is expected to
     * bound its own wait (see [TcpConnectivityProbe]); this only decides
     * whether a connection attempt happens at all.
     *
     * This is the unkeyed shape, for a task that resolves nothing - it bounds
     * how many bodies run at once and nothing more. A resolution-plus-connect
     * task must use the keyed overload below, or it can duplicate a lookup that
     * is already parked; see the object KDoc.
     *
     * **Where the two counters come back.** [SlotRelease] is released as the
     * answer is published - the release that matters, since a worker holding an
     * answer nobody needs is not a cost - and again in the wrapper's `finally`,
     * which is a no-op wherever the first ran. [BodyRelease] is released ONLY
     * in that `finally`: the thread is really busy until the body ends.
     */
    fun execute(task: Runnable): Boolean = executeReporting(task) == null

    /**
     * The shape behind [execute], naming the refusal instead of flattening it to
     * a `false`: null when the body was admitted and started, the [Refusal] that
     * stopped it otherwise. [claim] supplies that cause, so each cap is named
     * rather than folded into one.
     */
    internal fun executeReporting(task: Runnable): Refusal? {
        // `held`, not `admission`: a local of that name shadows the lock both
        // checks are taken under, and an edit meaning the lock would get this.
        val held = when (val claim = claim()) {
            is Claim.Refused -> return claim.cause
            is Claim.Granted -> claim.admission
        }
        // The body reaches a worker as a coroutine launched on [pool], not a
        // Runnable handed to the pool; the admission and the wrapper around the
        // body (the token, the contained throw, then the two releases in this
        // order) are unchanged. [claim] is the only refuser - a task it admits
        // always has a worker - so while [CAP_HEADROOM_THREADS] holds the pool
        // never rejects. If it ever did, the dispatcher cancels the job and
        // THEN resubmits the block, and a coroutine cancelled before it starts
        // never runs a default-start block: only start = ATOMIC still runs this
        // block, so its catch + finally return both counters from whichever
        // thread it lands on.
        scope.launch(pool, start = kotlinx.coroutines.CoroutineStart.ATOMIC) {
            // Published before the body so [answering] can find it, and
            // cleared in the `finally` so a reused worker never sees the
            // previous task's token.
            runningSlot.set(held.slot)
            try {
                try {
                    task.run()
                } catch (_: Throwable) {
                    // Swallowed on purpose - see the object KDoc ("A submitted
                    // body MAY throw"). The counters are given back by the
                    // `finally` regardless; what this catch controls is HOW the
                    // launched coroutine ends. Nothing runs between the body
                    // ending and that `finally` - no log, no I/O: the failure is
                    // already carried where its caller will find it (a
                    // production body publishes it to its deferred; a test body
                    // observes it through its own latch), and any work here -
                    // even a stderr write the test runner captures - runs BEFORE
                    // the counters come back, so it holds the slot and the
                    // thread while the next admission is judged and can refuse a
                    // body that should be admitted.
                }
            } finally {
                runningSlot.remove()
                // The two releases are in this order deliberately: the slot
                // first, so a body that HAS answered is already un-counted
                // by the time its thread is handed back, and the thread last,
                // because the thread is busy until this line.
                held.slot.release()
                held.body.release()
            }
        }
        return null
    }

    /**
     * Runs [compute] on a worker and releases [host]'s in-flight mark before the
     * value it produced can be observed by anybody.
     *
     * This is where the mark's real end is. It used to end in the keyed
     * [execute]'s wrapper `finally`, which runs only once the task body has
     * FULLY returned — and for a [java.util.concurrent.FutureTask] the caller's
     * waiter is released inside the callable, so the caller already held the
     * answer while the host was still marked: a back-to-back probe of the same
     * host was then refused by the single-flight check and answered "not
     * reachable" without a dial, which is indistinguishable from a server that
     * is down. Releasing here, in the `finally` that runs before the callable
     * returns its value, ties the mark's lifetime to the ANSWER rather than to
     * the worker's bookkeeping.
     *
     * Releasing here is also strictly safe for a still-parked lookup: an
     * interrupt from the caller's budget does not stop a name lookup, so
     * [compute] simply has not returned and this `finally` has not run. The
     * mark therefore still covers exactly the window in which the lookup is
     * genuinely outstanding. See [ProbeSingleFlight] for the precondition this
     * relies on.
     *
     * **The SLOT goes here too, and ONLY here.** This is the release the card
     * is about: the wrapper's `finally` runs after
     * [java.util.concurrent.FutureTask.set] has handed the caller its answer, so
     * a worker that has ALREADY answered still counted against
     * [MAX_WEDGED_PROBES], and the next probe of a healthy name was refused with
     * no lookup and no dial. Releasing here ties the slot to the ANSWER exactly
     * as it ties the mark.
     *
     * The release goes through the admission's own [SlotRelease], published by
     * [executeReporting] in [runningSlot] - the same right the wrapper's backstop
     * holds, not a second decrement. Off a worker running no admitted body
     * there is no token, which is inert: the count stays with whoever does own
     * the slot. The THREAD is deliberately NOT released here; see the object
     * KDoc.
     */
    fun <T> answering(host: String, compute: () -> T): T =
        try {
            compute()
        } finally {
            ProbeSingleFlight.releaseMark(host)
            // Null off a worker running no admitted body: nothing to release.
            runningSlot.get()?.release()
        }

    /**
     * Runs [task] for [host] on a daemon worker, or reports that there is
     * nowhere to put it - INCLUDING when a lookup for [host] is already parked.
     *
     * The mark, the key and the removal rules live in [ProbeSingleFlight]; this
     * is the boolean face of it, kept because [TcpConnectivityProbe] and the
     * tests call this name. See [executeReporting] for the refusal-naming
     * shape behind it.
     */
    fun execute(host: String, task: Runnable): Boolean =
        executeReporting(host, task) == null

    /**
     * The refusal-naming shape of the keyed [execute]; see [executeReporting].
     */
    internal fun executeReporting(host: String, task: Runnable): Refusal? =
        ProbeSingleFlight.submit(host, task)

    /**
     * Both admission checks, both taken, or neither - and WHICH refused: the two
     * caps mean the same thing to a caller and different things in a report, so
     * they cannot share one answer.
     *
     * **A refused attempt changes NOTHING.** Both conditions are tested before
     * either counter is written, so there is no partial update to undo: a
     * refused caller took no slot and no thread, so it has no token to release
     * and cannot release someone else's.
     */
    private fun claim(): Claim =
        synchronized(admission) {
            if (occupied.get() >= MAX_WEDGED_PROBES) {
                return@synchronized Claim.Refused(Refusal.NO_FREE_SLOT)
            }
            if (bodies.get() >= POOL_MAX_THREADS) {
                return@synchronized Claim.Refused(Refusal.NO_FREE_THREAD)
            }
            occupied.incrementAndGet()
            bodies.incrementAndGet()
            Claim.Granted(Admission(SlotRelease(), BodyRelease()))
        }

    /**
     * [claim]'s whole answer: the [Admission] it took, or the [Refusal] that
     * stopped it. A refused [Claim] carries no [Admission], so no token is
     * minted and nothing can be released - the property naming the cause must
     * not cost.
     */
    private sealed interface Claim {
        /** Both counters were taken; the two once-only rights to give them back. */
        data class Granted(val admission: Admission) : Claim

        /** Nothing was taken; [cause] names the cap that stopped the attempt. */
        data class Refused(val cause: Refusal) : Claim
    }

    /** What one successful [claim] hands back: the once-only right to release. */
    private class Admission(val slot: SlotRelease, val body: BodyRelease)
}

/** Daemon thread name prefix for the probe workers; descriptive, never exposed. */
private const val PROBE_THREAD_NAME = "dictation-reachability-probe"
