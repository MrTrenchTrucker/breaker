package dev.breaker.dictation.transport

import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * The daemon worker pool that resolution-plus-connection tasks run on, and the
 * bounded accounting that keeps one wedged lookup from starving the process.
 *
 * **Why this is not a single-threaded pool with a queue of one.** A name lookup
 * cannot be interrupted: `InetAddress.getAllByName` hands the name to the OS
 * resolver and waits on it, so a lookup against a blackholed resolver, a
 * captive portal or a VPN mid-handshake parks its thread until the platform
 * gives up on its own terms. Under the single worker + queue of one this
 * replaced, one such lookup parked the ONE thread that served every probe in
 * the JVM, and every later probe was refused without a connection attempt and
 * answered "not reachable" — which the domain read as "the server is down" and
 * routed on-device, with no way back short of a process restart. So a wedged
 * worker must be **counted and replaced**, not waited on, and two counters are
 * what make that replacement honest: [MAX_WEDGED_PROBES] for the slot,
 * [POOL_MAX_THREADS] for the thread.
 *
 * **TWO counters, released at TWO different moments. This asymmetry is the
 * design, and collapsing it is the defect.**
 *
 *  - [occupied] — the SLOT. Admitted and not yet ANSWERED. Given back at
 *    PUBLISH, by [answering]'s `finally`, which runs before the value it
 *    produced can be observed by anybody. A worker that has answered holds
 *    nothing worth counting, however long its own bookkeeping takes to unwind,
 *    so the next healthy name is not refused behind a finished lookup.
 *  - [bodies] — the THREAD. Held by any running body, answered or not. Given
 *    back when the BODY ENDS - it returns, or it throws - in the submitted
 *    wrapper's `finally`.
 *
 * They are deliberately not the same moment. A body that has published its
 * answer but is still inside its own task really is occupying an OS thread the
 * pool cannot hand to anybody else, so releasing the thread at publish would
 * let the pool over-subscribe itself into a rejection; and a body that has NOT
 * yet answered is genuinely still looking, so holding its slot past the answer
 * is what made a healthy address answer "not reachable" with no dial.
 *
 * **Admission needs BOTH, together.** [claim] checks the slot and the thread
 * under ONE lock and takes both or neither. Separate checks would not be a
 * style question: two callers could each pass one condition and fail the
 * other, and both proceed, so the bound this file promises would hold by luck.
 * A refused attempt leaves both counters exactly as it found them, because
 * nothing is written until both conditions have passed.
 *
 * **The SLOT cap.** [MAX_WEDGED_PROBES] is 2: one wedged lookup, plus one
 * healthy address that must still be dialled — a captive portal that swallows
 * DNS for one address must not be able to silence a different, perfectly good
 * one. Two is also the point at which "the network stack is comprehensively
 * down" describes the state better than "another address needs probing", and
 * it bounds the damage when every lookup hangs forever: however many hang, at
 * most [MAX_WEDGED_PROBES] UNANSWERED bodies are ever in flight.
 *
 * **The THREAD cap** is [POOL_MAX_THREADS] — the cap plus its one headroom
 * thread, which is the pool's own `maximumPoolSize`. It is the count of bodies
 * that have started and not yet ended, and it exists because the slot cap
 * alone cannot see post-answer work. The bound it enforces is the one the JDK
 * pool would enforce anyway, one layer up: a body handed a thread it does not
 * give back is a thread the next probe cannot have.
 *
 * **At either cap the answer is "not reachable", at once.** [execute] returns
 * false rather than throwing, and never runs the task on the calling thread:
 * a CallerRunsPolicy would move an unbounded, uninterruptible lookup onto
 * exactly the caller this pool exists to protect. The caller turns a false
 * into "not reachable" without a connection attempt ever being made.
 *
 * **The honest limit: post-answer work is not bounded.** A body that hangs
 * after answering holds its thread, and at [POOL_MAX_THREADS] further probes
 * are refused as not measured until it ends. Today's post-answer work is
 * socket close + unmark (microseconds; reasoned, not measured).
 *
 * **A refusal is NOT MEASURED and is NEVER CACHED.** [Refusal] names the cause,
 * and every one of its cases means the same thing to a caller: nothing was
 * looked up and nothing was dialled, so nothing was learned about the server.
 * The caller maps the whole family to its undialed shape, which is keyed on
 * "learned = false" and therefore stores nothing; see [Refusal]. Thread
 * saturation is one member of that family, not a reachability verdict.
 *
 * **Process-wide, and never shut down.** This is a file-level singleton on
 * purpose: an executor per probe instance would multiply threads — and file
 * descriptors — by the number of probes built, and nothing ever tears a probe
 * down, so a per-instance executor could only ever leak. Nothing owns this
 * pool's lifetime, so it is never shut down; the threads are daemon threads,
 */
internal object ProbeExecutor {

    /**
     * The most probe tasks this pool will run at once, wedged ones included.
     *
     * 2: one wedged lookup, plus one healthy address that must still be
     * dialled; see the object KDoc for why the number is two.
     *
     * This cap is a PROMISE this module makes and enforces, not a consequence
     * of how the pool happens to be sized: [claim] is the only thing that can
     * refuse a task, because the pool is deliberately one thread larger than
     * this constant (see [CAP_HEADROOM_THREADS]) so the JDK's own rejection is
     * never the thing holding the line.
     */
    const val MAX_WEDGED_PROBES: Int = 2

    /**
     * Threads the JDK pool is allowed beyond [MAX_WEDGED_PROBES].
     *
     * Exactly one, and it is load-bearing: it is what makes [claim] the only
     * refuser, so a task [claim] admits always has a worker and the
     * [RejectedExecutionException] catch in [execute] is a backstop behind it
     * rather than the mechanism. Sized to the cap instead, the JDK would refuse
     * overflow work indistinguishably from a cap refusal and a neutralised
     * [claim] guard would be undetectable.
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
     * `bodies < POOL_MAX_THREADS` exactly the tasks the pool can run - which is
     * what makes the [RejectedExecutionException] catch in [execute] a genuine
     * backstop rather than the load-bearing refusal.
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
        HOST_IN_FLIGHT("this host's lookup is already in flight"),

        /**
         * The pool's rejection handler refused the submit after [claim] let it
         * through; kept because our accounting and the JDK's are independent.
         */
        POOL_REJECTED("the probe worker pool rejected the task after admission"),
        ;

        /**
         * What refused the task, in words. A caller that reports this is
         * reporting that NOTHING was measured.
         */
        fun reason(): String = explanation
    }

    /** The one lock both admission checks are taken under; see [claim]. */
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
     */
    private val runningSlot: ThreadLocal<SlotRelease?> =
        ThreadLocal.withInitial<SlotRelease?> { null }

    /** Numbers the daemon threads so a wedged worker is identifiable in a dump. */
    private val started = AtomicInteger(0)

    /**
     * The worker pool, sized at [MAX_WEDGED_PROBES] PLUS one thread
     * ([CAP_HEADROOM_THREADS]) — the same number as [POOL_MAX_THREADS].
     *
     * That extra slot is not slack in the cap; see [CAP_HEADROOM_THREADS].
     * [SynchronousQueue] is what makes the sizing matter at all: with it the
     * JDK starts a thread per submitted task up to `maximumPoolSize`, so a pool
     * sized at exactly [MAX_WEDGED_PROBES] would let `ThreadPoolExecutor` enforce
     * the cap in place of [claim] - invisibly, and identically to the correct
     * refusal, which is to say not at all distinguishably.
     */
    private val pool = ThreadPoolExecutor(
        0,
        POOL_MAX_THREADS,
        WORKER_IDLE_MS,
        TimeUnit.MILLISECONDS,
        // A direct handoff: an idle worker takes the task itself, and when
        // there is no idle worker a new one is started, up to the pool's
        // maximum - which is the cap plus its one headroom thread. No queue,
        // because a queued task is a task nobody is going to run - its worker
        // is the thing that is wedged. With no core threads and a finite idle
        // window, a worker that FINISHES a task is offered the next one and
        // only retires after [WORKER_IDLE_MS] of quiet, so a steady stream of
        // healthy probes reuses the same threads rather than making one per
        // probe.
        SynchronousQueue(),
        { runnable ->
            Thread(runnable, "$PROBE_THREAD_NAME-${started.incrementAndGet()}").apply {
                isDaemon = true
            }
        },
        { _, _ ->
            // The backstop behind [bodies], kept as defence in depth. Under
            // normal operation this is UNREACHABLE: [claim] refuses a body once
            // [bodies] reaches [POOL_MAX_THREADS], which is this pool's own
            // `maximumPoolSize`, so the JDK has no reason to refuse a task
            // [claim] admitted. It stays because the accounting and the pool's
            // thread count are two independent mechanisms, and if they were ever
            // to disagree this is what stops an unbounded pile of threads.
            // Throwing is safe because [execute] turns it into a refusal and no
            // caller ever sees an exception.
            throw RejectedExecutionException("probe worker pool is saturated")
        },
    )

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
     *
     * **One token per [claim], never reused.** [executeReporting] mints one only
     * after a successful claim, so a refused admission holds no slot, has no
     * token, and cannot release one.
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
     * One token per [claim], like [SlotRelease]: a refused admission has none
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
        return try {
            pool.execute {
                // Published before the body so [answering] can find it, and
                // cleared in the `finally` so a reused worker never sees the
                // previous task's token.
                runningSlot.set(held.slot)
                try {
                    task.run()
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
            null
        } catch (_: RejectedExecutionException) {
            // The handler above - unreachable while [CAP_HEADROOM_THREADS]
            // holds, but wired for the case where it does not. Give both
            // counters straight back, or they would stay pinned with nothing
            // running and every later probe refused. The body never ran, so
            // these tokens' only release is this one.
            held.slot.release()
            held.body.release()
            Refusal.POOL_REJECTED
        }
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
