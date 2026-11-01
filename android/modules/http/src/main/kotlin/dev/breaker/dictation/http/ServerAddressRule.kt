package dev.breaker.dictation.http

/**
 * The one rule that says which address the module may call, given the address of the configured server.
 *
 * An https address is permitted for any host, exactly as the https check of [HttpRules] decides. A plain
 * http address is permitted only when the configured address is itself plain http and names the same host
 * and the same effective port: the host is compared after ASCII lower-casing and nothing else, a missing
 * port means 80, and the path, the query and the fragment are ignored. Every other address is not permitted.
 * A user who configured an https address is never moved down to plain http.
 *
 * The rule fails closed. An address with whitespace or a control character, an at sign in its authority, a
 * host outside letters, digits, hyphen, dot and underscore (or one bracketed IPv6 literal), a port outside
 * 1 to 65535, or a non-ASCII character in the scheme or the host is not permitted. A trailing dot is not
 * stripped, and an IPv6 literal is compared as written. An empty or unusable configured address permits no
 * plain http address at all. A non-ASCII character is refused in the scheme and the host; the path and the
 * query are not compared, so one there is accepted and left to the platform.
 *
 * It is pure: no network, no state, no logging.
 */
object ServerAddressRule {
    private const val SCHEME_SEPARATOR = "://"
    private const val PLAIN_SCHEME = "http"
    private const val PLAIN_PORT = 80
    private const val FIRST_PORT = 1
    private const val LAST_PORT = 65535
    private const val PORT_DIGITS = 5
    private const val IPV6_COLONS = 2
    private const val ASCII_CHARS = 128

    /** True when [address] may be called given the configured server address [configuredServer]. */
    fun permits(address: String, configuredServer: String): Boolean {
        if (HttpRules.isHttpsAddress(address)) return true
        val wanted = plainTarget(address) ?: return false
        val own = plainTarget(configuredServer) ?: return false
        return wanted == own
    }

    /** The host and the effective port of a plain http address. */
    private data class PlainTarget(val host: String, val port: Int)

    /** The target of [text] when it is a usable plain http address, otherwise null. */
    private fun plainTarget(text: String): PlainTarget? {
        if (text.any { it.isWhitespace() || Character.isISOControl(it) }) return null
        val separator = text.indexOf(SCHEME_SEPARATOR)
        if (separator < 0) return null
        val scheme = text.substring(0, separator)
        if (!scheme.all { it.code < ASCII_CHARS } || !scheme.equals(PLAIN_SCHEME, ignoreCase = true)) return null
        val afterScheme = text.substring(separator + SCHEME_SEPARATOR.length)
        val authority = afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }
        if (authority.contains('@')) return null
        return targetOf(authority)
    }

    /** The target of an authority that holds no user information, or null when its host or port is not usable. */
    private fun targetOf(authority: String): PlainTarget? {
        val hostEnd = hostEndOf(authority)
        if (hostEnd < 0) return null
        val host = authority.substring(0, hostEnd)
        val usable = if (host.startsWith('[')) isIpv6Literal(host) else isHostName(host)
        if (!usable) return null
        val port = portOf(authority.substring(hostEnd)) ?: return null
        return PlainTarget(host.lowercase(), port)
    }

    /** The index just past the host in [authority], or a negative number when a bracket is never closed. */
    private fun hostEndOf(authority: String): Int {
        if (authority.startsWith('[')) {
            val close = authority.indexOf(']')
            return if (close < 0) -1 else close + 1
        }
        val colon = authority.indexOf(':')
        return if (colon < 0) authority.length else colon
    }

    /** The effective port for the text after the host: nothing means the plain port, otherwise a colon and digits. */
    private fun portOf(afterHost: String): Int? {
        if (afterHost.isEmpty()) return PLAIN_PORT
        if (!afterHost.startsWith(':')) return null
        val digits = afterHost.substring(1)
        if (digits.isEmpty() || digits.length > PORT_DIGITS || !digits.all { isAsciiDigit(it) }) return null
        val value = digits.toInt()
        return if (value in FIRST_PORT..LAST_PORT) value else null
    }

    /** True for a non-empty host of ASCII letters, digits, hyphen, dot and underscore. */
    private fun isHostName(host: String): Boolean =
        host.isNotEmpty() && host.all { isAsciiLetter(it) || isAsciiDigit(it) || it == '-' || it == '.' || it == '_' }

    /** True for one bracketed literal that holds at least two colons and only hex digits, colons and dots. */
    private fun isIpv6Literal(bracketed: String): Boolean {
        val inner = bracketed.substring(1, bracketed.length - 1)
        if (inner.count { it == ':' } < IPV6_COLONS) return false
        return inner.all { isHexDigit(it) || it == ':' || it == '.' }
    }

    private fun isAsciiDigit(c: Char): Boolean = c in '0'..'9'

    private fun isAsciiLetter(c: Char): Boolean = c in 'a'..'z' || c in 'A'..'Z'

    private fun isHexDigit(c: Char): Boolean = isAsciiDigit(c) || c in 'a'..'f' || c in 'A'..'F'
}
