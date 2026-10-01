package dev.breaker.dictation.transport

import dev.breaker.dictation.core.port.Clock
import dev.breaker.dictation.core.port.ConnectivityProbe
import java.net.InetAddress
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CancellationException
import java.util.concurrent.ExecutionException
import java.util.concurrent.FutureTask
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
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
 * only when the platform gives up on its own. Until then, probes for that
 * address keep answering from this rule, not from a stale success.
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
        val target = try {
            target()
        } catch (_: RuntimeException) {
            return false
        } ?: return false
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
     * short-circuits it; it always dials and answers from what it just
     * measured.
     */
    fun refresh(): Boolean {
        val target = try {
            target()
        } catch (_: RuntimeException) {
            return false
        } ?: return false
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
     *   [refresh], which must always dial.
     */
    private fun probeAndStore(target: Target, allowFreshCache: Boolean): Boolean {
        synchronized(this) {
            if (allowFreshCache) {
                freshEntryFor(target)?.let { return it.reachable }
            }
            val reachable = runProbe(target)
            cache = CacheEntry(target.host, target.port, reachable, clock.nowEpochMillis())
            return reachable
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
     */
    private fun runProbe(target: Target): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CONNECT_TIMEOUT_MS)
        val task = FutureTask { connect(target, deadline) }
        try {
            PROBE_EXECUTOR.execute(task)
        } catch (_: RuntimeException) {
            // The worker pool refused the task, so no connection was attempted.
            return false
        }
        val remainingNanos = deadline - System.nanoTime()
        return try {
            task.get(remainingNanos, TimeUnit.NANOSECONDS)
        } catch (_: TimeoutException) {
            // The parked worker is told to stop, but it ends on its own terms:
            // a blocking socket call is not guaranteed to react to an interrupt.
            task.cancel(true)
            false
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

        /**
         * One daemon thread shared by every probe in the process, with a queue
         * of one.
         *
         * It is a file-level singleton on purpose: an executor per probe
         * instance would multiply threads — and file descriptors — by the number
         * of probes built, and nothing ever tears a probe down, so a per-instance
         * executor could only ever leak.
         *
         * When the thread and the queue are both busy the task is **discarded
         * and the probe answers false**, rather than run on the calling thread.
         * Running it inline would move an unbounded, uninterruptible connect
         * onto a caller that is supposed to return within
         * [CONNECT_TIMEOUT_MS], which is precisely the guarantee this class
         * exists to keep. A saturated queue means a previous probe is still
         * stuck on a dead network, so "not reachable" is the honest answer.
         *
         * Daemon threads so a live thread cannot hold up process exit; the
         * executor is never shut down because nothing owns its lifetime.
         */
        private val PROBE_EXECUTOR: ExecutorService = ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(1),
            { runnable -> Thread(runnable, PROBE_THREAD_NAME).apply { isDaemon = true } },
        )
    }
}

/** Daemon thread name for the shared probe worker; descriptive, never exposed. */
private const val PROBE_THREAD_NAME = "dictation-reachability-probe"

/** How many dotted groups a valid IPv4 literal has. */
internal const val V4_GROUP_COUNT = 4

/** How many digits one group of a valid IPv4 literal may have. */
private const val V4_MAX_GROUP_DIGITS = 3

/** The highest value any group of a valid IPv4 literal may take. */
private const val V4_MAX_GROUP_VALUE = 255

/**
 * True only for text that is an address literal in its entirety — never for
 * text that merely LOOKS like one. Being strict here is what keeps malformed
 * text answered without a lookup or a dial.
 *
 * **IPv4** is exactly [V4_GROUP_COUNT] groups of one to
 * [V4_MAX_GROUP_DIGITS] digits, each in `0..`[V4_MAX_GROUP_VALUE].
 *
 * **IPv6** (a host containing ':') is text built only from hex digits, '.'
 * and ':' whose FIRST character is a hex digit or ':'. The hex character set
 * is what makes that an IPv6 test rather than a digit test: a hex IPv6 literal
 * is mostly letters, so accepting only digits rejected every real IPv6 address
 * and sent it to the resolver.
 *
 * **The first-character rule is load-bearing, and so is the range rule.**
 * [InetAddress.getByName] attempts its numeric parse ONLY when the first
 * character is a hex digit or ':'. If that parse then fails and the text
 * contains no ':', the JDK has no IPv6 branch left and falls through to a
 * REAL SYSTEM DNS LOOKUP — measured at 70-150 ms on JDK 17. A shape-only test
 * ("four all-digit groups", "every character is hex, dot or colon") lets
 * exactly those texts through, so the parse fails *inside* the JDK where this
 * module can no longer see or refuse it. "999.1.1.1", "1.2.3.256" and
 * "4294967295.0.0.0" are all four all-digit groups, and ".:", "..::1" and
 * ".1::" all pass a character-set test; none of them is an address.
 *
 * Hoisted out of [TcpConnectivityProbe.Target] so it can be table-tested on
 * its own: it is the only place the range and first-character rules are
 * observable, because a lookup through [InetAddress] never reaches this seam.
 */
internal fun isAddressLiteral(host: String): Boolean =
    if (host.contains(':')) isIpv6Literal(host) else isIpv4Literal(host)

/**
 * True for exactly four dotted groups of one to three digits, each 0-255.
 *
 * "999.1.1.1" fails on the first group and "1.2.3.256" on the last, which is
 * the whole point: a group count without a range is what handed those two
 * strings to a real DNS lookup.
 */
private fun isIpv4Literal(host: String): Boolean {
    val groups = host.split('.')
    if (groups.size != V4_GROUP_COUNT) return false
    return groups.all { group ->
        group.length in 1..V4_MAX_GROUP_DIGITS &&
            group.all { it.isDigit() } &&
            group.toInt() in 0..V4_MAX_GROUP_VALUE
    }
}

/**
 * True for colon-separated address text whose first character is a hex digit
 * or ':' and whose remaining characters are hex digits, '.' or ':'.
 *
 * Text that fails the first-character condition — ".1::", "..::1" — is refused
 * here rather than offered to the JDK as a name.
 */
private fun isIpv6Literal(host: String): Boolean {
    if (host.isEmpty()) return false
    val first = host[0]
    if (!(first.isHexDigit() || first == ':')) return false
    return host.all { it.isHexDigit() || it == '.' || it == ':' }
}

/** True for 0-9, a-f and A-F: the digits an address literal is written in. */
private fun Char.isHexDigit(): Boolean =
    this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'
