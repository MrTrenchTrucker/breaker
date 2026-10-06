package dev.breaker.dictation.transport

import java.net.InetAddress

// STAGE 2d pure move: the probe's self-contained value types and the
// file-private parse constants, out of TcpConnectivityProbe. Same
// package, internal visibility, byte-identical bodies de-indented to
// top level; the class keeps using every one of them by the same bare
// name.

/**
 * One probe's answer, and whether it was learned at all.
 *
 * "Not reachable" is one word for three different facts: a dial that was
 * made and failed, a task that never ran because [ProbeExecutor] had
 * nowhere to put it (every slot held by a lookup that has not returned, or
 * this host's own lookup still parked), and a task that ran but whose
 * budget expired before it produced anything. All three are correct answers
 * to a caller's question, and the caller gets one boolean either way. Only
 * the first is a MEASUREMENT. The other two resolved nothing and dialled
 * nothing, so there is nothing learned about the server, and caching either
 * would serve it for [CACHE_TTL_MS] — answering the first question asked
 * after the host comes back out of that cache instead of dialling a server
 * that is there again.
 *
 * [learned] is what [probeAndStore] keys that decision on. A genuine
 * failure — a real socket refused, a name that does not resolve, a body
 * that threw — is a measurement of this address and is cached, which is
 * the whole purpose of a negative cache.
 *
 * **The cost of not caching [UNANSWERED].** A name whose resolution outlives
 * the budget is re-probed every question, not once per [CACHE_TTL_MS]
 * window. A timed-out connect IS measured and cached, unless the caller's
 * own budget wins that race first.
 */
internal enum class ProbeAttempt(val reachable: Boolean, val learned: Boolean) {
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
internal class CacheEntry(
    val host: String,
    val port: Int,
    val reachable: Boolean,
    val storedAtMillis: Long,
)

/** A configured host and port, with the host already stripped of brackets. */
internal class Target(val host: String, val port: Int) {
    /**
     * The addresses to try for this target. A valid address literal (see
     * [isAddressLiteral]) is used as-is and never reaches the resolver;
     * every other colon host, and every host that merely LOOKS numeric
     * without being a valid address, is refused here and reaches neither
     * the resolver nor [InetAddress.getByName] nor the connector, so the
     * probe answers "not reachable" with no lookup or dial at all. A scoped
     * IPv6 literal (one carrying a %zone suffix) is refused by design: zone
     * ids vary by platform and the population is narrow, so the refusal
     * fails safe - it yields no address, dials nothing and answers "not
     * reachable" without a lookup or an exception.
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

internal const val SCHEME_SEPARATOR = "://"
internal const val HTTPS_PORT = 443
internal const val HTTP_PORT = 80
internal const val MIN_PORT = 1
internal const val MAX_PORT = 65_535
