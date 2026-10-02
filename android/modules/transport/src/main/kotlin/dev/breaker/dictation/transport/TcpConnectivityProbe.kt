package dev.breaker.dictation.transport

import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.ConnectivityProbe
import java.net.InetAddress
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

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
 * **A parked lookup's reach is the PROCESS, not the address.** The old KDoc here
 * said probes for that address keep answering from this rule; that was false.
 * An interrupt does not stop a name lookup, so a wedged resolution holds a
 * worker, and what it affects is every other probe in the process: probes of a
 * DIFFERENT address, and later probes of the same one, all go through the same
 * bounded pool. One wedged lookup therefore (a) makes another address's probe
 * wait for a worker rather than dial, and (b) if every worker is wedged,
 * refuses probes outright so they answer "not reachable" without a connection
 * attempt — a healthy server reported down, and the domain routes every
 * dictation on-device. [ProbeExecutor] is what bounds that: each wedged worker
 * is counted so a spare one is created for the next address, and the count
 * comes back down when the lookup finally returns, so the pool recovers without
 * a restart. A lookup that is still in flight also makes a LATER probe of the
 * same host answer "not reachable" at once, with no second lookup: while the
 * name is unresolved there is nothing to dial, and a merely slow host is
 * indistinguishable from a wedged one for the duration - a deliberate price
 * for keeping a slot free for other addresses.
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

    /**
     * One probe's answer, and whether it was learned at all.
     *
     * "Not reachable" is one word for three different facts: a dial that was
     * made and failed, a task that never ran because [ProbeExecutor] had
     * nowhere to put it (every slot held by a lookup that has not returned, or
     * this host's own lookup still parked), and a task that ran but whose
     * budget expired before it produced anything. All three are correct
     * answers to a caller's question, and the caller gets one boolean either
     * way. Only the first is a MEASUREMENT. The other two resolved nothing
     * and dialled nothing, so there is nothing learned about the server, and
     * caching either would serve it for [CACHE_TTL_MS] — answering the first
     * question asked after the host comes back out of that cache instead of
     * dialling a server that is there again.
     *
     * [learned] is what [probeAndStore] keys that decision on. A genuine
     * failure — a real socket refused, a name that does not resolve, a body
     * that threw — is a measurement of this address and is cached, which is
     * the whole purpose of a negative cache.
     *
     * **The cost of not caching [UNANSWERED].** A name whose resolution outlives
     * the budget is re-probed on every question instead of once per
     * [CACHE_TTL_MS] window; the routing answer is the same, and a connect that
     * times out is a measured "not reachable" and IS cached.
     */
    private enum class ProbeAttempt(val reachable: Boolean, val learned: Boolean) {
        /** A connection was accepted. */
        REACHABLE(true, true),

        /** The task ran and reported that the server was not there. Cached. */
        UNREACHABLE(false, true),

        /** The task was refused before it ran; no lookup, no dial. Not cached. */
        UNDIALED(false, false),

        /**
         * The budget ran out while the task was still going. Not cached: the
         * address that would have been dialled was never obtained, so nothing
         * is known rather than measured.
         */
        UNANSWERED(false, false),
    }

    /** A cached answer and the address it was measured for. */
    private class CacheEntry(
        val host: String,
        val port: Int,
        val reachable: Boolean,
        val storedAtMillis: Long,
    )

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
     * This is the one caller that reaches [probeAndStore] with
     * `allowFreshCache = false`, so a cached answer — however fresh — never
     * short-circuits it; it answers from the probe it just ran.
     *
     * **The exceptions, honestly stated.** It dials whenever a dial can be
     * MADE, and it produces no answer to keep in two cases: no worker is
     * available at all ([ProbeExecutor] at
     * [ProbeExecutor.MAX_WEDGED_PROBES], or this host's own lookup still in
     * flight), or the budget runs out before the task produces one. Both answer
     * false, and — because neither learned anything about the server — both
     * cache nothing. The next question asked once the lookup returns is a real
     * probe and really dials.
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
        if (entry.host != target.host || entry.port != target.port) return null
        val age = clock.nowEpochMillis() - entry.storedAtMillis
        return if (age in 0 until CACHE_TTL_MS) entry else null
    }

    /**
     * Probes once and stores the answer.
     *
     * Synchronized so that a cold probe opens one socket rather than one per
     * concurrent caller, and so a refresh cannot interleave with a cold probe
     * and leave the older answer cached last.
     *
     * The monitor is held **across** the connection attempt. That is a
     * deliberate blocking-under-lock decision: the alternative is a second
     * connection per simultaneous caller, and reachability checks are cheap to
     * collapse and expensive to duplicate. The wait is bounded by the budget
     * below, and the lock is on this instance only, so it cannot block an
     * unrelated object.
     *
     * **Double-checked locking.** Holding the monitor only makes concurrent
     * callers wait *for each other*; it does not make any of them skip the
     * dial. Every caller that missed the cache outside the monitor would
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
        synchronized(this) {
            if (allowFreshCache) {
                freshEntryFor(target)?.let { return it.reachable }
            }
            val attempt = runProbe(target)
            // Only an answer that LEARNED something is cached. An attempt that
            // was refused before it ran, and one whose budget expired while it
            // was still running, learned nothing: caching either false would
            // serve it for the whole window and hide a server that has just
            // come back from the very question meant to find it. See
            // [ProbeAttempt].
            if (attempt.learned) {
                cache = CacheEntry(target.host, target.port, attempt.reachable, clock.nowEpochMillis())
            }
            return attempt.reachable
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
     * (case-folded - see [ProbeExecutor]), so a
     * second probe of a wedged host neither starts a second lookup nor spends
     * a second of the cap, leaving the remaining slots to other addresses. The
     * task wraps its body in [ProbeExecutor.answering] so that mark ends when
     * THIS answer is produced, not after the worker tidies up - otherwise the
     * next back-to-back probe of a host just answered would be refused and
     * report a healthy server down. The wait below therefore happens only for
     * a task that really is running, and stays bounded by the same budget.
     */
    private fun runProbe(target: Target): ProbeAttempt {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MS)
        val task = FutureTask { ProbeExecutor.answering(target.host) { connect(target, deadline) } }
        if (!ProbeExecutor.execute(target.host, task)) {
            // Either every worker is held by a lookup that has not returned, or
            // this host's own lookup is still parked. In both cases no
            // connection was attempted. Answering now is the honest answer and
            // keeps the caller inside its budget - and UNDIALED, so the caller
            // caches nothing on the strength of it.
            return ProbeAttempt.UNDIALED
        }
        val remainingNanos = deadline - System.nanoTime()
        val reachable = try {
            task.get(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            // The budget is spent and the task is still running, so nothing is
            // known: the address that would have been dialled was never
            // obtained, and the connection that would have been made was never
            // established. The parked worker is told to stop, but it ends on
            // its own terms - a blocking socket call is not guaranteed to
            // react to an interrupt - so whatever it eventually produces is
            // not an answer this caller ever sees. UNANSWERED, and the caller
            // caches nothing on the strength of it: a false that was never
            // measured, served for the whole window, is the same defect as
            // caching a refusal.
            task.cancel(true)
            return ProbeAttempt.UNANSWERED
        } catch (_: ExecutionException) {
            false
        } catch (_: CancellationException) {
            false
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            task.cancel(true)
            false
        } catch (_: RuntimeException) {
            false
        }
        // The task ran to completion and reported its own outcome, so this
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

    /** A configured host and port, with the host already stripped of brackets. */
    private class Target(val host: String, val port: Int) {
        /**
         * The addresses to try for this target. A valid address literal (see
         * [isAddressLiteral]) is used as-is and never reaches the resolver;
         * every other colon host, and every host that merely LOOKS numeric
         * without being a valid address, is refused here and reaches neither
         * the resolver nor [InetAddress.getByName] nor the connector, so the
         * probe answers "not reachable" with no lookup or dial at all.
         */
        fun addresses(resolver: HostResolver): List<InetAddress> {
            if (isAddressLiteral(host)) return literalAddresses()
            if (host.contains(':') || looksNumeric(host)) return emptyList()
            return try {
                resolver.resolve(host)
            } catch (_: RuntimeException) {
                emptyList()
            }
        }

        /**
         * True for text made only of digits and dots — text someone wrote as an
         * address and got wrong, such as "999.1.1.1" or "1.2.3". Refused
         * alongside the colon hosts: it names no server, so asking a name server
         * about it asks about a name that was never a name. A real host name
         * contains a letter or a hyphen, so this cannot swallow one.
         */
        private fun looksNumeric(host: String): Boolean =
            host.isNotEmpty() && host.all { it.isDigit() || it == '.' }

        private fun literalAddresses(): List<InetAddress> = try {
            listOf(InetAddress.getByName(host))
        } catch (_: Exception) {
            emptyList()
        }
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

        private const val SCHEME_SEPARATOR = "://"
        private const val HTTPS_PORT = 443
        private const val HTTP_PORT = 80
        private const val MIN_PORT = 1
        private const val MAX_PORT = 65_535
    }
}
