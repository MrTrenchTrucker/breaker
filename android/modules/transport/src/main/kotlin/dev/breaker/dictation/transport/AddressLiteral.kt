package dev.breaker.dictation.transport

/*
 * What counts as an address literal, kept in a file of its own rather than in
 * TcpConnectivityProbe.kt's flow: it is the only place the range and
 * first-character rules are observable, because a lookup through
 * InetAddress.getAllByName never reaches this seam, so it has to be
 * table-testable on its own. Hoisted out purely to keep that file inside its
 * size cap; the rules and the prose explaining them are unchanged.
 */

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
 * here rather than offered to the JDK as a name. A scoped IPv6 literal (one
 * carrying a %zone suffix) is refused by design: zone ids vary by platform and
 * the population is narrow, so the refusal fails safe - it yields no address,
 * dials nothing and answers "not reachable" without a lookup or an exception.
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
