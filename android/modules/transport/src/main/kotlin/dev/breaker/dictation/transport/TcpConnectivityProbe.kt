package dev.breaker.dictation.transport

import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.ConnectivityProbe
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Answers whether the configured Local Server is reachable, with a TCP connect
 * under a short budget and a short-lived cache.
 *
 * The answer is the whole point of this class: reachable means the server
 * accepted a TCP connection on its configured address within
 * [CONNECT_TIMEOUT_MS]. A refused connection, a timeout, a name that does not
 * resolve and an unusable configuration all answer "not reachable" — the
 * domain routes on one boolean and must never have to interpret a failure.
 *
 * **What the budget does and does not cover.** The [CONNECT_TIMEOUT_MS] budget is
 * measured by the calling thread and is spent on *both* name resolution and the
 * connection attempt, because resolution runs on a worker and the caller only
 * waits for the pair to finish. A connection-level timeout on its own would not
 * have been enough: an address object built from a host name resolves eagerly,
 * so the socket's own timeout can be spent before the socket is ever handed a
 * name to look up. Resolution is still not instant — a slow or blackholed
 * resolver can outlive the budget — so when the budget runs out the answer is
 * "not reachable" and the worker is interrupted, but the parked thread finishes
 * only when the platform gives up on its own. A budget that expires that way
 * caches nothing either: the probe never learned an answer worth keeping.
 *
 * **A parked lookup's reach is the PROCESS, not the address.** The old KDoc
 * here said probes for that address keep answering from this rule; that was
 * false. An interrupt does not stop a name lookup, so a wedged resolution holds
 * a worker, and what it affects is every other probe in the process: probes of
 * a DIFFERENT address, and later probes of the same one, all go through the
 * same bounded pool. One wedged lookup therefore (a) makes another address's
 * probe wait for a worker rather than dial, and (b) if every worker is wedged,
 * refuses probes outright so they answer "not reachable" without a connection
 * attempt — a healthy server reported down, and the domain routes every
 * dictation on-device. [ProbeExecutor] is what bounds that: each wedged worker
 * is counted so a spare one is created for the next address, and the count
 * comes back down when the lookup finally returns, so the pool recovers without
 * a restart. The cap counts lookups THAT HAVE NOT ANSWERED YET, not lookups in
 * flight generally: a worker hands its slot back the moment it PUBLISHES its
 * answer, not when its task body returns, so an already-answered worker must
 * never keep the cap full - that would make (b) fire and refuse a healthy
 * address with no dial while nothing at all is wedged. A lookup that is still
 * in flight also makes a LATER probe of the same host answer "not reachable" at
 * once, with no second lookup: while the name is unresolved there is nothing to
 * dial, and a merely slow host is indistinguishable from a wedged one for the
 * duration - a deliberate price for keeping a slot free for other addresses.
 *
 * **Cache.** One answer is cached for [CACHE_TTL_MS], keyed on the address the
 * configuration resolved to, so editing the server address takes effect
 * immediately instead of after the remaining TTL. Within the window the answer
 * is served without touching the network.
 *
 * **Thread safety.** Safe to call from any number of threads. The cache is a
 * single immutable entry behind one volatile field, so a reader can never see a
 * host from one probe paired with a timestamp from another.
 *
 * This class holds no audio and makes no routing decision: it answers whether
 * the server is there, and choosing what to do about that answer belongs to the
 * domain.
 *
 * @param serverUrlProvider supplies the configured server address, read fresh
 *   on every probe so an edit takes effect without rebuilding this object. It
 *   is deliberately the narrowest thing that can supply the answer: a supplier
 *   of one string, not the whole settings store.
 * @param clock the time source for the cache window.
 * @param resolver turns a host name into addresses.
 * @param connector performs the connection.
 */
class TcpConnectivityProbe internal constructor(
    private val serverUrlProvider: () -> String,
    private val clock: Clock,
    private val resolver: HostResolver,
    private val connector: TcpConnector,
) : ConnectivityProbe {

    /**
     * The production probe: the platform's own name lookup and a real socket.
     *
     * This is the only shape `android/app` needs, and it keeps the injectable
     * seam [HostResolver]/[TcpConnector] off the public signature — those types
     * are internal, so they cannot appear where another module can see them.
     */
    constructor(
        serverUrlProvider: () -> String,
        clock: Clock,
    ) : this(serverUrlProvider, clock, SystemHostResolver, SocketTcpConnector)

    /** One immutable entry, published as a whole: two separate fields could be read torn. */
    @Volatile
    private var cache: CacheEntry? = null

    /**
     * Returns true when the configured Local Server accepted a TCP connection
     * within [CONNECT_TIMEOUT_MS], or when a cached answer inside
     * [CACHE_TTL_MS] already says so.
     *
     * Answers false for an unusable configuration, a failing settings read, a
     * resolver that fails and a connector that fails: a caller on the
     * dictation path has no useful recovery for those. A throwing [Clock] is a
     * caller defect, not a server fault, and propagates — catching it would
     * report a healthy server unreachable behind a false nobody can question.
     */
    override fun isServerReachable(): Boolean {
        val target = target() ?: return false
        freshEntryFor(target)?.let { return it.reachable }
        return probeAndStore(target, allowFreshCache = true)
    }

    /**
     * Re-probes now, ignoring any cached answer, and caches the fresh result.
     *
     * Useful after the user changes the server address or after a failure worth
     * retrying immediately. Like [isServerReachable], it answers false rather
     * than throwing for the same four server-side failures.
     *
     * This is the one caller that reaches [probeAndStore] with `allowFreshCache
     * = false`, so a cached answer — however fresh — never short-circuits it;
     * it answers from the probe it just ran. **The exceptions, honestly
     * stated.** It dials whenever a dial can be MADE, and it produces no answer
     * to keep in two cases: no worker is available at all ([ProbeExecutor] at
     * [ProbeExecutor.MAX_WEDGED_PROBES], or this host's own lookup still in
     * flight - see the class KDoc), or the budget runs out before the task
     * produces one. Both answer false, and — because neither learned anything
     * about the server — both cache nothing. The next question asked once the
     * lookup returns is a real probe and really dials.
     */
    fun refresh(): Boolean {
        val target = target() ?: return false
        return probeAndStore(target, allowFreshCache = false)
    }

    /**
     * Returns the cached entry for [target] when it is still inside the cache
     * window, or null when a probe is needed.
     */
    private fun freshEntryFor(target: Target): CacheEntry? {
        val entry = cache ?: return null
        if (!entry.host.equals(target.host, ignoreCase = true) || entry.port != target.port) return null
        val age = clock.nowEpochMillis() - entry.storedAtMillis
        return if (age in 0 until CACHE_TTL_MS) entry else null
    }

    /**
     * The per-instance lock that serialises cold probes. A [Mutex] now, held
     * **across** the connection attempt exactly as the monitor it replaced was
     * (a deliberate blocking-under-lock decision, for the reason in the class
     * KDoc). The monitor it replaces was reentrant and this [Mutex] is not;
     * nothing in this class ever reenters the lock, so that difference is
     * invisible here. It is untimed exactly as the monitor was (there is no
     * timed acquire), which is what lets the critical section in [probeAndStore]
     * be the one place in this class that suspends: the lock lives inside the
     * [runBlocking] bridge and nowhere else.
     */
    private val probeLock = Mutex()

    /**
     * Probes once and stores the answer.
     *
     * Locked so that a cold probe opens one socket rather than one per
     * concurrent caller, and so a refresh cannot interleave with a cold probe
     * and leave the older answer cached last.
     *
     * The lock is held **across** the connection attempt. That is a
     * deliberate blocking-under-lock decision: the alternative is a second
     * connection per simultaneous caller, and reachability checks are cheap to
     * collapse and expensive to duplicate. The wait is bounded by the budget
     * below, and the lock is on this instance only, so it cannot block an
     * unrelated object.
     *
     * **Double-checked locking.** Holding the lock only makes concurrent
     * callers wait *for each other*; it does not make any of them skip the
     * dial. Every caller that missed the cache outside the lock would
     * otherwise queue and then probe anyway — a storm of N callers on a cold
     * cache costs N dials, serially, and the last one waits N times the budget.
     * So when [allowFreshCache] is set the cache is re-read here, inside the
     * lock and before [runProbe]: the first caller through probes and stores,
     * and every caller behind it finds the fresh answer and returns it without
     * a second socket. That is the point of the whole lock.
     *
     * [allowFreshCache] is false only for [refresh], whose contract is to
     * re-probe **now** and answer from the fresh result: letting it return a
     * cached answer would make a refresh a no-op whenever the last answer is
     * still inside the window, and the caller would never learn that the
     * server came back. The two paths therefore cannot be collapsed into one
     * that always consults the cache.
     *
     * @param allowFreshCache true for the [isServerReachable] path, which may
     *   serve an answer another caller has just measured; false for
     *   [refresh], which must never be served a cached answer.
     */
    private fun probeAndStore(target: Target, allowFreshCache: Boolean): Boolean {
        return try {
            runBlocking {
                probeLock.withLock {
                    if (allowFreshCache) {
                        freshEntryFor(target)?.let { return@withLock it.reachable }
                    }
                    val attempt = runProbe(target)
                    // Only an answer that LEARNED something is cached. An attempt
                    // that was refused before it ran, and one whose budget expired
                    // while it was still running, learned nothing: caching either
                    // false would serve it for the whole window and hide a server
                    // that has just come back from the very question meant to
                    // find it. See [ProbeAttempt].
                    if (attempt.learned) {
                        cache = CacheEntry(target.host, target.port, attempt.reachable, clock.nowEpochMillis())
                    }
                    return@withLock attempt.reachable
                }
            }
        } catch (_: InterruptedException) {
            // kotlinx.coroutines 1.11.0, jvm/src/Builders.kt: the blocked
            // caller parks in BlockingCoroutine.joinBlocking, whose loop runs
            //   if (Thread.interrupted()) cancelCoroutine(InterruptedException())
            // at :58 and, once the job is complete,
            //   (state as? CompletedExceptionally)?.let { throw it.cause }
            // at :68. createCauseException (common/src/JobSupport.kt:749-752)
            // passes a Throwable through unwrapped, so the rethrow at :68 is
            // the PLAIN InterruptedException, not a CancellationException, and
            // Thread.interrupted() at :58 has already cleared the caller's
            // flag. Re-arm it and answer without learning: the dispatched body
            // is not a structured child of the cancelled job, so it ends on
            // its own terms on its worker, bounded by its own connect
            // deadline, and whatever it produces is not an answer this caller
            // ever sees.
            Thread.currentThread().interrupt()
            false
        }
    }

    /**
     * Resolves and connects as one task, waited on under the budget.
     *
     * The budget is measured with [System.nanoTime] rather than the injected
     * [Clock]: a clock is a domain seam whose value a caller may legitimately
     * freeze in a test, and a frozen clock would make a stalled probe look
     * instant. Elapsed time is not a domain concept, so it uses the platform's
     * monotonic timer.
     *
     * If every worker in [ProbeExecutor] is wedged the task is never started:
     * there is no waiting, and "not reachable" is answered at once. The same
     * answer comes back, for the same reason and at the same cost, when a
     * lookup for this host is ALREADY in flight: the host's NAME is the key
     * (case-folded - see [ProbeExecutor]), so a second probe of a wedged host
     * neither starts a second lookup nor spends a second of the cap, leaving
     * the remaining slots to other addresses. The body wraps itself in
     * [ProbeExecutor.answering] so that mark ends when THIS answer is produced,
     * not after the worker tidies up - for the reason given in the class KDoc.
     *
     * Suspends inside [probeAndStore]'s [runBlocking]: the admission is a
     * plain call, and the wait below is the one suspension in this class. The
     * body computes its answer INSIDE [ProbeExecutor.answering]'s mark - the
     * reachable boolean, or the caught throw held in a Result - and publishes it
     * only AFTER answering has released the host's mark and the slot; so a
     * caller that probes the same host again the instant its answer becomes
     * observable finds an already-unmarked host, never refused by the
     * single-flight check for an answer that already exists - the
     * release-at-publish contract the answer-order test in this module pins. The
     * worker publishes its answer through a [CompletableDeferred] (the
     * coroutine replacement for the FutureTask the wait used to read), and the
     * wait therefore happens only for a body that really is running, and stays
     * bounded by the same budget - the budget's `withTimeout` cancels the wait
     * when it is spent, and a body that throws publishes its throw to the
     * caller through the deferred instead of swallowing it.
     */
    private suspend fun runProbe(target: Target): ProbeAttempt {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MS)
        // The body's result, published from the worker. A [CompletableDeferred]
        // replaces the FutureTask: the worker completes it, and the caller
        // waits on it under the budget.
        val result = CompletableDeferred<Boolean>()
        val task = Runnable {
            // The answer is COMPUTED inside [answering], which releases host's
            // mark and the slot before its return; this lambda never touches
            // `result`. connect runs once here, and either yields the reachable
            // boolean or, contained in a kotlin.Result (the current try/catch's
            // single catch, throwing body included), becomes one.
            val answer: Result<Boolean> = ProbeExecutor.answering(target.host) {
                runCatching { connect(target, deadline) }
            }
            // Publish only AFTER answering has released the mark and the slot: a
            // caller that probes the same host again the moment its answer is
            // observable finds an already-unmarked host, never refused by the
            // single-flight check for an answer that already exists - the
            // release-at-publish contract the test relies on. On success publish
            // the boolean; on failure publish its contained throw exceptionally.
            // The body still returns normally on every path: a thrown connect is
            // contained in the Result, so the launched coroutine completes normally.
            // Were the throw not contained, it would complete the coroutine
            // exceptionally and reach the uncaught-exception handler of the thread
            // the body runs on - whose documented default ends the process on
            // Android. The containment is what preserves that contract.
            if (answer.isSuccess) {
                result.complete(answer.getOrThrow())
            } else {
                result.completeExceptionally(answer.exceptionOrNull()!!)
            }
        }
        if (!ProbeExecutor.execute(target.host, task)) {
            // No connection was attempted. Answering now is the honest answer
            // and keeps the caller inside its budget - and UNDIALED, so the
            // caller caches nothing on the strength of it.
            return ProbeAttempt.UNDIALED
        }
        val remainingNanos = deadline - System.nanoTime()
        val reachable = try {
            withTimeout(TimeUnit.NANOSECONDS.toMillis(remainingNanos)) { result.await() }
        } catch (_: TimeoutCancellationException) {
            // The budget is spent and the body is still running, so nothing is
            // known: see [ProbeAttempt.UNANSWERED]. The parked worker ends on
            // its own terms - a blocking socket call is not guaranteed to react
            // to anything - so whatever it eventually completes the deferred
            // with is not an answer this caller ever sees.
            return ProbeAttempt.UNANSWERED
        } catch (_: CancellationException) {
            // The wait was cancelled for a reason that is not the budget: this
            // caller gave up, and nothing was measured.
            return ProbeAttempt.UNANSWERED
        } catch (_: Throwable) {
            // The body threw: its exception was re-thrown out of [result.await],
            // so this attempt is a measurement of this address and is cached
            // like any other failure - a real refusal, a name that does not
            // resolve, and a body that threw are all things the network actually
            // said.
            return ProbeAttempt.UNREACHABLE
        }
        // The body ran to completion and reported its own outcome, so this
        // attempt is a measurement of this address and is cached like any
        // other failure - a real refusal, a name that does not resolve, and a
        // body that threw are all things the network actually said.
        return if (reachable) ProbeAttempt.REACHABLE else ProbeAttempt.UNREACHABLE
    }

    /**
     * Runs on the worker: resolve, then connect, trying addresses in the
     * resolver's order until one is accepted.
     *
     * Each attempt is given whatever is left of the shared deadline rather than
     * a fresh full budget, so a second address cannot double the time the
     * caller waits.
     */
    private fun connect(target: Target, deadline: Long): Boolean {
        val addresses = target.addresses(resolver)
        for (address in addresses) {
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMillis <= 0L) return false
            if (connector.connect(address, target.port, remainingMillis.toInt())) return true
        }
        return false
    }

    /**
     * Reads and validates the configured address.
     *
     * Returns null for anything this probe must not turn into a connection:
     * a blank value, an unsupported scheme, and syntactically broken input all
     * answer "not reachable" without touching the network.
     *
     * **This method does not throw, and [isServerReachable]/[refresh] rely on
     * that.** Both callers used to wrap this call in a catch for
     * `RuntimeException` that could never fire, and both were deleted: of the
     * statements here exactly one can throw — [serverUrlProvider] at the top —
     * and that one is caught inside this method. Every parse helper is
     * bounds-safe by construction, and the load-bearing case is
     * [parsePort]'s `text.toIntOrNull()`, whose null-returning parse is what
     * stops a [NumberFormatException] escaping. Replacing that with
     * [Integer.parseInt], a regex, or a URI/IDN parse makes this method throw
     * again — at which point a throw out of [isServerReachable] or [refresh] is
     * a caller-visible crash on the dictation path, so either restore a catch
     * at the caller or keep the parse null-returning. Note what must NOT be
     * added here: [Clock.nowEpochMillis] is deliberately outside every catch,
     * because a throwing clock is a caller defect that propagates rather than a
     * healthy server being reported unreachable behind a false.
     */
    private fun target(): Target? {
        val raw = try {
            serverUrlProvider()
        } catch (_: RuntimeException) {
            return null
        } ?: return null
        val trimmed = raw.trim()

        val schemeEnd = trimmed.indexOf(SCHEME_SEPARATOR)
        val scheme: String?
        val rest: String
        if (schemeEnd >= 0) {
            scheme = trimmed.substring(0, schemeEnd).lowercase()
            rest = trimmed.substring(schemeEnd + SCHEME_SEPARATOR.length)
        } else {
            scheme = null
            rest = trimmed
        }

        if (scheme == null) {
            // No scheme: only "<host>" or "<host>:<port>", with no whitespace and
            // no path separator. Anything looser would let a stray character
            // through as part of a host name.
            if (trimmed.any { it.isWhitespace() } || trimmed.contains('/')) return null
        } else {
            when (scheme) {
                "https", "http" -> Unit
                // Any other scheme is malformed as far as this probe is
                // concerned, and is answered without a connection.
                else -> return null
            }
        }

        // Drop any path, query or fragment; only the authority is probed.
        val authority = if (scheme == null) rest else rest.takeWhile { it != '/' && it != '?' && it != '#' }
        // Credentials in the address are not supported: treating the whole
        // authority as a host name would send the lookup somewhere else.
        if (authority.contains('@')) return null

        val defaultPort = when (scheme) {
            "https", null -> HTTPS_PORT
            else -> HTTP_PORT
        }
        val (host, port) = splitAuthority(authority, defaultPort) ?: return null
        if (host.isEmpty() || port == null || port !in MIN_PORT..MAX_PORT) return null
        return Target(host, port)
    }

    /**
     * Splits "[host]" / "<host>" / "<host>:<port>" into its parts, applying
     * [defaultPort] when the text carries none.
     *
     * Returns null when the text cannot be a host name at all: empty, an
     * unterminated bracket, or a port that is not a number in range.
     */
    private fun splitAuthority(
        text: String,
        defaultPort: Int?,
    ): Pair<String, Int?>? {
        if (text.isEmpty()) return null
        if (text.startsWith('[')) {
            val close = text.indexOf(']')
            if (close < 0) return null
            val host = text.substring(1, close)
            val tail = text.substring(close + 1)
            if (tail.isEmpty()) return if (defaultPort == null) null else host to defaultPort
            if (!tail.startsWith(':')) return null
            return host to parsePort(tail.substring(1))
        }
        if (text.contains('/') || text.any { it.isWhitespace() }) return null
        val colon = text.lastIndexOf(':')
        if (colon < 0) return text to defaultPort
        // More than one colon and no brackets is an address literal written
        // without them; guessing which part is the host would be wrong.
        if (text.indexOf(':') != colon) return null
        return text.substring(0, colon) to parsePort(text.substring(colon + 1))
    }

    private fun parsePort(text: String): Int? {
        if (text.isEmpty() || text.any { it !in '0'..'9' }) return null
        return text.toIntOrNull()
    }

    companion object {
        /**
         * The budget for one resolution-plus-connection attempt. Long enough to
         * survive a busy local network, short enough that a dictation is never
         * held behind a probe.
         */
        const val CONNECT_TIMEOUT_MS: Long = 1_500L

        /** How long an answer is served without touching the network. */
        const val CACHE_TTL_MS: Long = 30_000L

    }
}
