package dev.breaker.dictation.transport

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
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
 * the JVM; the next task filled the single queue slot, and from then on every
 * probe — every address, every instance, every later call — was refused without
 * a connection attempt and answered "not reachable". The domain read that as
 * "the server is down" and routed on-device, with no way back short of a
 * process restart. So a wedged worker must be **counted and replaced**, not
 * waited on: the count is what lets the pool grow to [MAX_WEDGED_PROBES], and
 * the count comes back down on its own so a released lookup leaves the pool
 * whole again. The pool itself is built one thread larger than that cap, so
 * the cap is upheld by [claim] - code this module owns - rather than by the
 * JDK's rejection path; see [CAP_HEADROOM_THREADS].
 *
 * **The cap.** [MAX_WEDGED_PROBES] is 2. One worker is the one wedged lookup; the
 * second is what keeps a healthy address dialable while the first is stuck —
 * a captive portal that swallows DNS for one address must not be able to
 * silence a different, perfectly good address. Two is also the point at which
 * "the network stack is comprehensively down" is a better description than
 * "another address needs probing": beyond a wedged lookup AND a concurrent
 * healthy probe, every further simultaneous hang is the same dead network seen
 * again (a captive portal AND a VPN AND a mistyped address all at once), and a
 * probe that cannot get a worker answers "not reachable" immediately, which is
 * the honest answer for that state. Two also bounds the damage when every
 * lookup hangs forever: however many hang, at most [MAX_WEDGED_PROBES] task
 * bodies are ever in flight, never an unbounded pile. (The JDK pool is sized
 * one thread above the cap so [claim] is the only gate; that spare thread
 * stays idle and is never given a task, because a third task never gets past
 * [claim].)
 *
 * **At the cap the answer is "not reachable", at once.** [execute] returns
 * false rather than throwing, and never runs the task on the calling thread.
 * A CallerRunsPolicy — the obvious alternative for a rejecting pool — would
 * move an unbounded, uninterruptible lookup onto exactly the caller this pool
 * exists to protect, who is supposed to return within
 * [TcpConnectivityProbe.CONNECT_TIMEOUT_MS]. The caller turns a false into "not
 * reachable" without a connection attempt ever being made.
 *
 * **How the count moves.** [occupied] is the number of tasks handed to the
 * pool and not yet finished, wedged or merely in flight:
 *
 *  - up: [execute] claims a slot with a CAS loop before submitting, so the
 *    count can never exceed [MAX_WEDGED_PROBES] and a rejected submit gives the
 *    slot straight back.
 *  - down: the submitted wrapper decrements in a `finally`, so it runs on the
 *    wedged worker's OWN thread the moment the lookup returns — or the moment
 *    a task that was cancelled before it started declines to run. A lookup that
 *    returns hands its worker back and a later probe runs on it; the pool never
 *    needs to grow a fresh thread to recover from a transient wedge.
 *
 * **Per-host single-flight.** The cap alone is not enough: two probes of the
 * SAME host, seconds apart, each start their own lookup, and two parked
 * lookups for one unreachable name consume the whole cap - after which a
 * perfectly healthy server is answered "not reachable" without a dial and the
 * domain routes every dictation on-device. So while a lookup for a host is in
 * flight, a later probe of that host is answered not reachable AT ONCE and
 * starts no second lookup and takes no second slot. One permanently hung host
 * therefore holds at most ONE of the [MAX_WEDGED_PROBES] slots, and the other
 * stays available to every other address. See the keyed [execute].
 *
 * **What single-flight does NOT fix.** It is a bound on the damage ONE wedged
 * name can do, not a cure for wedged lookups, and both remaining limits are
 * real:
 *
 *  - Two DIFFERENT hung hosts still fill the cap of [MAX_WEDGED_PROBES], and
 *    from there every further probe is refused. Single-flight stops a repeat of
 *    ONE host; it does not admit a third distinct one. A network that wedges
 *    two names at once is still described as comprehensively down.
 *  - A re-probe of a parked host answers not reachable at once BY DESIGN, so
 *    for as long as that lookup is parked a host that is merely SLOW is
 *    indistinguishable from one that is permanently wedged, and the caller
 *    receives that answer with no evidence any dial was attempted. That is the
 *    price of not spending a second slot, and it is paid deliberately: the
 *    alternative is a cap overrun that silences healthy addresses too.
 *
 * **Process-wide, and never shut down.** This is a file-level singleton on
 * purpose: an executor per probe instance would multiply threads — and file
 * descriptors — by the number of probes built, and nothing ever tears a probe
 * down, so a per-instance executor could only ever leak. Nothing owns this
 * pool's lifetime, so it is never shut down; the threads are daemon threads, so
 * a live worker cannot hold up process exit.
 */
internal object ProbeExecutor {

    /**
     * The most probe tasks this pool will run at once, wedged ones included.
     *
     * 2: one wedged lookup, plus one healthy address that must still be
     * dialled. It must cover the realistic concurrent hangs without allowing
     * unbounded thread growth if EVERY lookup hangs forever. See the object
     * KDoc for why the third simultaneous hang is answered "not reachable"
     * rather than given a third thread.
     *
     * This cap is a PROMISE this module makes and enforces, not a consequence
     * of how the pool happens to be sized. It is upheld solely by [claim],
     * whose CAS loop is the only thing that can refuse a task - the worker
     * pool is deliberately one thread larger than this constant (see
     * [CAP_HEADROOM_THREADS]) precisely so the JDK's own rejection is never
     * the thing holding the line. Were the pool sized to this cap instead, the
     * bound would hold by luck of the pool's internals, the constant would not
     * be load-bearing, and a test could not tell "correctly refused" from
     * "accidentally refused", so removing [claim]'s guard would change nothing
     * observable.
     */
    const val MAX_WEDGED_PROBES: Int = 2

    /**
     * Threads the JDK pool is allowed beyond [MAX_WEDGED_PROBES].
     *
     * Exactly one, and it is load-bearing. The bound this class promises is
     * enforced by [claim], in code this module owns and can test. If the
     * pool's `maximumPoolSize` were the cap itself, the JDK would silently be
     * the thing refusing overflow work: [execute] would return the same
     * `false` for "cap reached" as for "pool saturated", with the task body
     * never run under either path and nothing to distinguish them. That makes
     * [MAX_WEDGED_PROBES] an accident of pool sizing rather than a promise -
     * and a neutralised [claim] guard would be undetectable. With one spare
     * thread the JDK always accepts a task [claim] lets through, so the ONLY
     * possible refuser is [claim], and "the task body did not run" becomes
     * evidence about our code.
     */
    private const val CAP_HEADROOM_THREADS: Int = 1

    /**
     * How long a worker that has FINISHED a task waits for the next one before
     * retiring. Long enough that the ordinary case - a probe every few seconds
     * - reuses its workers; short enough that a burst cannot pin threads for
     * long after the traffic stops. It does not bound a wedged worker: that
     * one is inside the lookup and never reaches this window.
     */
    private const val WORKER_IDLE_MS = 30_000L

    /** Tasks handed to the pool and not yet finished; the wedged count. */
    private val occupied = AtomicInteger(0)

    /**
     * The names whose lookup is in flight right now, and so already hold a
     * slot of [occupied].
     *
     * The key is the HOST ALONE, never host-and-port: the thing that wedges is
     * a per-NAME resolution, and a blackholed resolver wedges that name for
     * every port it appears on. A port in the key would let one wedged name
     * take the whole cap a port at a time. The host is also case-folded, so
     * one wedged name is one key however it was spelled; see [keyFor].
     *
     * **Why [ConcurrentHashMap]'s key set and not a synchronised [java.util.HashSet].**
     * The set is written by whichever caller wins the mark, read by other
     * callers deciding whether to answer at once, and mutated on worker threads
     * as lookups return. All three happen concurrently, and the decision that
     * matters - `add` returning `false` for a name somebody already holds - has
     * to be a SINGLE atomic step, or two callers could both see the name
     * absent, both start a lookup, and both take a slot, which is the defect
     * this map exists to prevent. Hand-rolled synchronisation around a plain
     * set would have to be right at every one of those sites, and the failure
     * mode of getting it wrong is a silent cap overrun.
     *
     * Deliberately NOT cleared by a caller that has given up waiting: see the
     * keyed [execute].
     */
    private val inFlight: MutableSet<String> = ConcurrentHashMap.newKeySet<String>()

    /** Numbers the daemon threads so a wedged worker is identifiable in a dump. */
    private val started = AtomicInteger(0)

    /**
     * The worker pool, sized at [MAX_WEDGED_PROBES] PLUS one thread
     * ([CAP_HEADROOM_THREADS]).
     *
     * That extra slot is intentional and is not slack in the cap. With a
     * [SynchronousQueue] the JDK starts a thread per submitted task up to
     * `maximumPoolSize`, so sizing the pool at exactly [MAX_WEDGED_PROBES]
     * would let `ThreadPoolExecutor` enforce the cap in place of [claim] -
     * invisibly, and identically to the correct refusal, which is to say not
     * at all distinguishably. One thread of headroom means any task [claim]
     * admits is guaranteed a worker, so [claim] is the sole gate at the
     * boundary and the behaviour under that boundary is observable from
     * outside this file.
     */
    private val pool = ThreadPoolExecutor(
        0,
        MAX_WEDGED_PROBES + CAP_HEADROOM_THREADS,
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
            // The backstop behind [occupied], kept as defence in depth. Under
            // normal operation this is UNREACHABLE: the pool carries one more
            // thread than [MAX_WEDGED_PROBES] (see [CAP_HEADROOM_THREADS]), so
            // every task [claim] admits has a worker waiting for it and the JDK
            // has no reason to refuse. It stays because the accounting and the
            // pool's thread count are two independent mechanisms, and if they
            // were ever to disagree this is what stops an unbounded pile of
            // threads. Throwing is safe because [execute] turns it into
            // `false` and no caller ever sees it.
            throw RejectedExecutionException("probe worker pool is saturated")
        },
    )

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
     */
    fun execute(task: Runnable): Boolean {
        if (!claim()) return false
        return try {
            pool.execute {
                try {
                    task.run()
                } finally {
                    // On the worker's own thread, so the slot comes back the
                    // instant the lookup returns - whether it returned an
                    // answer, threw, or was cancelled before it ran.
                    occupied.decrementAndGet()
                }
            }
            true
        } catch (_: RejectedExecutionException) {
            // The handler above - unreachable while [CAP_HEADROOM_THREADS]
            // holds, but wired for the case where it does not. Give the slot
            // straight back, or the count would stay pinned at the cap with
            // nothing running and every later probe refused.
            occupied.decrementAndGet()
            false
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
     * genuinely outstanding.
     */
    fun <T> answering(host: String, compute: () -> T): T =
        try {
            compute()
        } finally {
            inFlight.remove(keyFor(host))
        }

    /**
     * Runs [task] for [host] on a daemon worker, or reports that there is
     * nowhere to put it - INCLUDING when a lookup for [host] is already parked.
     *
     * **The key is one NAME, deliberately.** [keyFor] folds case, so a host
     * already being looked up under another spelling is joined rather than
     * looked up again. And the key is [host] alone, not host-and-port: the
     * thing that wedges is `resolver.resolve(host)`, which takes a name and
     * nothing else. DNS is per-NAME: a blackholed resolver wedges that name
     * for every port it appears on, so a host+port key would let
     * `a.invalid:8443` park and then admit `a.invalid:9443` as a different key
     * and refill the cap - the same defect by another route. This key therefore
     * differs on purpose from the CACHE key in [TcpConnectivityProbe], which is
     * host+port: the cache answers "is this ADDRESS reachable", while this map
     * guards a per-NAME resource. One resource, one key.
     *
     * **Where the mark is cleared.** Its lifetime must be the ANSWER's, not the
     * task's: a caller that already holds a result must not be able to block
     * the next probe of that host. So a task that produces a value wraps its
     * body in [answering], whose `finally` runs BEFORE the value reaches the
     * caller's waiter, and that is the mark's real end. The wrapper `finally`
     * here is the backstop for the paths where no value is ever produced -
     * cancelled before it started, or a body that never ran [answering] - and
     * on every path where the task never runs at all: a refused claim or a
     * rejected submit removes it in this function. Both removals are of an
     * absent-tolerant set, so the backstop cannot clear a mark that has just
     * been handed to a later probe. Missing the submit-side removal is
     * strictly worse than the defect this prevents, because the host would stay
     * marked for the life of the process, never be probed again, and have every
     * answer look like a legitimate "not reachable".
     *
     * **Where the mark is NOT cleared: the caller's timeout.** The caller
     * answers its own budget with `cancel(true)`, and cancel only INTERRUPTS -
     * the callable keeps running on the wedged worker. Releasing the mark there
     * would admit the next probe to start a second lookup of a name whose first
     * lookup is still parked, which is the whole bug. The mark belongs to the
     * TASK's lifetime, not to the caller's wait.
     *
     * See "What this does not fix" on the object for the honest limit.
     */
    fun execute(host: String, task: Runnable): Boolean {
        // Atomic against concurrent callers: exactly one of them wins the
        // absent-to-present transition for this name, and a `false` here means
        // somebody else's lookup for this very name is still parked.
        val key = keyFor(host)
        if (!inFlight.add(key)) return false
        val single = Runnable {
            try {
                task.run()
            } finally {
                inFlight.remove(key)
            }
        }
        val started = execute(single)
        if (!started) inFlight.remove(key)
        return started
    }

    /**
     * The [inFlight] key for [host]: the name as DNS sees it, not as the
     * configuration happened to type it.
     *
     * DNS names are case-insensitive, and `InetAddress.getAllByName` folds
     * case before it asks the platform, so "Box.local" and "box.local" are
     * one name to every resolver that has ever existed. Keying on the raw
     * text therefore gives ONE wedged name TWO slots and TWO lookups into the
     * same blackholed resolver - precisely the cap overrun [inFlight] exists to
     * prevent - and the join never happens either, because the second spelling
     * is a stranger to the set and is resolved and dialled afresh against a
     * name already known to be stuck.
     *
     * **Locale.ROOT, never a bare [String.lowercase].** Kotlin's no-argument
     * `lowercase()` uses the DEFAULT locale, and under a Turkish default
     * locale "I" lowercases to a dotless "ı": "IIS" would become "ııs" and
     * would NOT equal the "iis" that the same name produces under any other
     * locale - reintroducing the very split this function removes, invisibly,
     * and only on the devices whose users would be least likely to explain it.
     * ROOT is locale-independent, so every device computes the same key.
     *
     * Deliberately inside this file, at the point of use, rather than at the
     * caller: [execute] and [answering] are separate entry points and the
     * mark has to be removed by the SAME key it was added under, so a caller
     * that lowercased one call site and not the other would leave a mark that
     * is never cleared. Folding it in one place makes that impossible.
     */
    private fun keyFor(host: String): String = host.lowercase(Locale.ROOT)

    /**
     * Claims one of the [MAX_WEDGED_PROBES] slots, or reports that all of them
     * are held by tasks that have not finished.
     *
     * A CAS loop rather than a plain `incrementAndGet` so the cap is enforced
     * under concurrency: two callers racing at the last slot cannot both take
     * it, and neither can push the count past the cap and leave it there when
     * the pool refuses their task.
     */
    private fun claim(): Boolean {
        while (true) {
            val held = occupied.get()
            if (held >= MAX_WEDGED_PROBES) return false
            if (occupied.compareAndSet(held, held + 1)) return true
        }
    }
}

/** Daemon thread name prefix for the probe workers; descriptive, never exposed. */
private const val PROBE_THREAD_NAME = "dictation-reachability-probe"
