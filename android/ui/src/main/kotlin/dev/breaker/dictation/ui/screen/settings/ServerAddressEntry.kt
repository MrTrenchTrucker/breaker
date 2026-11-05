package dev.breaker.dictation.ui.screen.settings

/*
 * The server address the person types on the settings screen, and the one small rule
 * that decides whether it may be stored.
 *
 * The rule is a deliberate copy of the address rule of the http module. The ui module
 * reaches only core, so it cannot call that one, and the copy is kept to the same shape:
 * an http or https address, a host, an optional port and an optional path, with no user
 * name, query, fragment or IPv6 literal. It is pure Kotlin, so a plain JVM test loads it.
 *
 * The rule fails closed. It never changes the text it accepts, except for cutting the
 * spaces at its two ends, so what is stored is what the person typed.
 */

/** The address rule for the settings screen's server field, and the key the field writes. */
internal object ServerAddressEntry {
    /** The stored key of the server address; the same name the settings file uses. */
    const val KEY = "server_url"

    /** The longest address accepted, in characters, after its spaces are cut. */
    private const val MAX_LENGTH = 2000

    /** The highest port number accepted. */
    private const val MAX_PORT = 65535

    /** The longest port text accepted, in digits. */
    private const val MAX_PORT_DIGITS = 5

    /** The highest character code of a text that is still ASCII. */
    private const val ASCII_LIMIT = 127

    /** The text to store for [typed], or null when the address is refused. Empty after trimming gives "". */
    fun accepted(typed: String): String? {
        val trimmed = typed.trim()
        if (trimmed.isEmpty()) return ""
        if (trimmed.length > MAX_LENGTH) return null
        if (trimmed.any { it.code > ASCII_LIMIT || it.isWhitespace() || it.isISOControl() }) return null
        val split = trimmed.indexOf("://")
        if (split < 0) return null
        if (!isHttpScheme(trimmed.substring(0, split))) return null
        val rest = trimmed.substring(split + 3)
        if (rest.any { it == '@' || it == '?' || it == '#' }) return null
        val slash = rest.indexOf('/')
        val authority = if (slash < 0) rest else rest.substring(0, slash)
        val path = if (slash < 0) "" else rest.substring(slash)
        if (!isValidAuthority(authority)) return null
        if (!isValidPath(path)) return null
        return trimmed
    }

    /** True for the text http or https in any ASCII letter case. */
    private fun isHttpScheme(scheme: String): Boolean {
        val lower = scheme.lowercase()
        return lower == "http" || lower == "https"
    }

    /** A host of letters, digits, dot, dash and underscore, then an optional port after the last colon. */
    private fun isValidAuthority(authority: String): Boolean {
        if (authority.isEmpty() || authority.contains('[') || authority.contains(']')) return false
        val colon = authority.lastIndexOf(':')
        val host = if (colon < 0) authority else authority.substring(0, colon)
        if (colon >= 0 && !isValidPort(authority.substring(colon + 1))) return false
        return host.isNotEmpty() && host.all { isHostCharacter(it) }
    }

    /** One to five digits, with a value from 1 to the highest port. */
    private fun isValidPort(port: String): Boolean =
        port.length in 1..MAX_PORT_DIGITS && port.all { it in '0'..'9' } && port.toInt() in 1..MAX_PORT

    private fun isHostCharacter(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '_'

    /**
     * An empty path is valid. Otherwise one trailing slash is dropped, and then no segment after the
     * first slash may be empty, a single dot or two dots.
     */
    private fun isValidPath(path: String): Boolean {
        val body = path.removeSuffix("/")
        if (body.isEmpty()) return true
        return body.substring(1).split('/').none { it.isEmpty() || it == "." || it == ".." }
    }
}
