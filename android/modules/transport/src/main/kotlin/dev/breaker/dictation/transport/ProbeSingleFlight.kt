package dev.breaker.dictation.transport

import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-host single-flight: one in-flight mark per DNS name, taken before the
 * submit and released when the ANSWER exists.
 *
 * **Why this is separate from [ProbeExecutor].** The two own different pieces
 * of state and answer different questions. [ProbeExecutor] owns the bounded
 * SLOT and THREAD accounting and decides whether a body may run at all. This
 * file owns the per-NAME mark, a [ConcurrentHashMap] whose only question is
 * "is this one name already being looked up?". A wedged name is a per-name
 * resource, not a per-process one: two probes of the SAME host seconds apart
 * must share one lookup, or two parked lookups for one unreachable name
 * consume the whole cap and a perfectly healthy server is answered "not
 * reachable" without a dial. So while a lookup for a host is in flight, a
 * later probe of that host is answered not reachable AT ONCE and starts no
 * second lookup and takes no second slot.
 *
 * **What single-flight does NOT fix.** It is a bound on the damage ONE wedged
 * name can do, not a cure for wedged lookups, and the limit that remains is
 * real: two DIFFERENT hung hosts still fill the cap, and from there every
 * further probe is refused. Single-flight stops a repeat of ONE host; it does
 * not admit a third distinct one.
 *
 * The mark's accounting KDoc lives on the members below; this file is only
 * where they moved, so the mark's rules are readable next to the code that
 * implements them.
 */
internal object ProbeSingleFlight {

    /**
     * The names whose lookup is in flight right now, and so already hold a
     * slot of [ProbeExecutor.MAX_WEDGED_PROBES].
     *
     * The key is the HOST ALONE, never host-and-port: the thing that wedges is
     * a per-NAME resolution, and a blackholed resolver wedges that name for
     * every port it appears on. A port in the key would let one wedged name
     * take the whole cap a port at a time. The host is also case-folded, so
     * one wedged name is one key however it was spelled; see [keyFor].
     *
     * **Why a [ConcurrentHashMap] and not a synchronised [java.util.HashSet].**
     * The map is written by whichever caller wins the mark, read by other
     * callers deciding whether to answer at once, and mutated on worker threads
     * as lookups return. All three happen concurrently, and the decision that
     * matters - the absent-to-present transition for a name - has to be a
     * SINGLE atomic step, or two callers could both see the name absent, both
     * start a lookup, and both take a slot, which is the defect this map exists
     * to prevent. Hand-rolled synchronisation around a plain set would have to
     * be right at every one of those sites, and the failure mode of getting it
     * wrong is a silent cap overrun.
     *
     * **Why the value is a mark object and not a unit.** The name alone cannot
     * identify who is allowed to clear it. Between [ProbeExecutor.answering]'s
     * removal and the keyed [submit]'s backstop, another probe of the SAME
     * name can claim its own mark, and an unkeyed removal cannot tell the two
     * apart - it would clear the LATER probe's mark while that probe's lookup
     * was still parked, admitting a second lookup of a wedged name and two
     * slots out of [ProbeExecutor.MAX_WEDGED_PROBES]. So each admission puts a
     * FRESH identity here, and both of [submit]'s removals are conditional on
     * that exact identity ([ConcurrentHashMap.remove] with both key and value),
     * which succeeds only while the very mark that put the entry there is
     * still the one present.
     *
     * Deliberately NOT cleared by a caller that has given up waiting: see the
     * keyed [ProbeExecutor.execute].
     */
    private val inFlight: ConcurrentHashMap<String, Any> = ConcurrentHashMap<String, Any>()

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
     * body in [ProbeExecutor.answering], whose `finally` runs BEFORE the value
     * reaches the caller's waiter, and that is the mark's real end. The wrapper
     * `finally` here is the backstop for the paths where no value is ever
     * produced - cancelled before it started, or a body that never ran
     * [ProbeExecutor.answering] - and on every path where the task never runs
     * at all: a refused admission or a rejected submit removes it in this
     * function. Each of this function's two removals is CONDITIONAL on the
     * mark this admission installed - it removes the name only while that exact
     * mark is still the one present - so the backstop clears its OWN entry and
     * never a mark that has since been handed to a later probe of the same
     * name. An unkeyed removal there would clear exactly that:
     * [ProbeExecutor.answering] releases a name as soon as an answer exists,
     * which is before this task body has returned, so the window between the
     * two is one in which another probe of the same name is legitimately
     * admitted and holds its own mark. Missing the submit-side removal is
     * strictly worse than the defect this prevents, because the host would stay
     * marked for the life of the process, never be probed again, and have every
     * answer look like a legitimate "not reachable".
     *
     * **The SLOT needs no work here.** [ProbeExecutor.execute] is where the
     * slot and the thread are claimed and where [ProbeExecutor.answering] finds
     * the admission's [ProbeExecutor.SlotRelease]. Routing both through that
     * one admission is what keeps each once-only: there is one token per
     * successful claim.
     *
     * **Where the mark is NOT cleared: the caller's timeout.** The caller
     * answers its own budget with `cancel(true)`, and cancel only INTERRUPTS -
     * the callable keeps running on the wedged worker. Releasing the mark there
     * would admit the next probe to start a second lookup of a name whose first
     * lookup is still parked, which is the whole bug. The mark belongs to the
     * TASK's lifetime, not to the caller's wait.
     *
     * Returns the [ProbeExecutor.Refusal] that stopped the task, or null when
     * the body was admitted and started. A refusal here learned nothing: no
     * lookup ran, no dial was made, so the caller must treat it exactly as it
     * treats a refused start - answered not reachable AT ONCE and cached never.
     */
    internal fun submit(host: String, task: Runnable): ProbeExecutor.Refusal? {
        // Atomic against concurrent callers: exactly one of them wins the
        // absent-to-present transition for this name, and a refusal here means
        // somebody else's lookup for this very name is still parked.
        val key = keyFor(host)
        // A fresh identity per admission, so this task can later prove the mark
        // it installed is still the one present before clearing it.
        val mark = Any()
        if (inFlight.putIfAbsent(key, mark) != null) return ProbeExecutor.Refusal.HOST_IN_FLIGHT
        val single = Runnable {
            try {
                task.run()
            } finally {
                // Both key AND mark: unconditional removal here would clear a
                // LATER probe's mark for this name, whose lookup is still
                // parked, admitting a second lookup of a wedged name.
                inFlight.remove(key, mark)
            }
        }
        val refusal = ProbeExecutor.executeReporting(single)
        if (refusal != null) inFlight.remove(key, mark)
        return refusal
    }

    /**
     * Removes [host]'s mark unconditionally. The mark's REAL end.
     *
     * **Precondition.** Call this only from [ProbeExecutor.answering], on the
     * worker running a body that the keyed admission started FOR THE SAME NAME.
     * That is what makes the plain removal correct rather than merely
     * convenient: [ProbeExecutor.answering] runs INSIDE that task, so the mark
     * it removes is unambiguously its own. While that mark is present no other
     * caller can put a mark for the same name into [inFlight] - the keyed
     * admission takes a name only on an absent-to-present transition, and the
     * entry is present for exactly as long as that call runs. So there is no
     * window in which this removal could discard somebody else's mark. Called
     * for a name the task does not hold the mark for - a stray call, or a task
     * started under one spelling and answered under another - it would instead
     * evict an unrelated probe's live mark, which is the defect the keyed
     * removals in [submit] exist to prevent.
     */
    internal fun releaseMark(host: String) {
        inFlight.remove(keyFor(host))
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
     * is a stranger to the map and is resolved and dialled afresh against a
     * name already known to be stuck.
     *
     * **Locale.ROOT, spelled out.** Kotlin's no-argument `lowercase()` is
     * already ROOT, so that spelling is not a bug either; naming the locale
     * makes the independence something a reader checks, not something they
     * must remember about the language. The hazard is a fold consulting the
     * DEFAULT locale - the deprecated `toLowerCase()`, which looks like the
     * modern spelling and is not, or `lowercase(Locale.getDefault())`: a
     * Turkish default folds "IIS" to "ııs", not the "iis" it makes elsewhere.
     *
     * Deliberately inside this file, at the point of use, rather than at the
     * caller: [submit] and [releaseMark] are separate entry points and the mark
     * has to be removed by the SAME key it was added under, so a caller that
     * lowercased one call site and not the other would leave a mark that is
     * never cleared. Folding it in one place makes that impossible.
     */
    private fun keyFor(host: String): String = host.lowercase(Locale.ROOT)
}