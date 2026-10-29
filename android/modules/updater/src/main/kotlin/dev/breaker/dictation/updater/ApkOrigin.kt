package dev.breaker.dictation.updater

/** Why an apk address does not belong to the configured server address. */
internal enum class OriginRefusal {
    /** The address does not start with https. */
    NOT_HTTPS,

    /** The address holds a character, host, port or authority form that this check does not accept. */
    NOT_USABLE,

    /** The authority carries user information. */
    USER_INFO,

    /** The address carries a fragment. */
    FRAGMENT,

    /** The host names differ. */
    HOST_MISMATCH,

    /** The ports differ. */
    PORT_MISMATCH,
}

/**
 * Checks that an apk address belongs to the configured server address.
 *
 * Limits: the check reads https addresses only, with its own small parser. It accepts no IPv6 literal host
 * (an address with a bracketed host is refused): this is a stated limit, because the Local Server is
 * configured by an IPv4 address or a host name. Percent escapes are never decoded. Host names are compared
 * as ASCII text after lower-casing; an IPv4 address is compared as text too, with no normalisation, so the
 * check fails closed. The path and the query are not part of the origin and are not judged. The base address
 * is judged by the same rules, and its path is ignored.
 */
internal object ApkOrigin {
    /** The port used when an address names none. */
    internal const val DEFAULT_PORT: Int = 443

    private const val PREFIX = "https://"

    /** Returns the refusal for [apkUrl] under [serverBase], or null when the apk address belongs to the server. */
    fun refusalFor(serverBase: String, apkUrl: String): OriginRefusal? {
        refusalForBase(serverBase)?.let { return it }
        problemOf(apkUrl)?.let { return it }
        if (hostOf(serverBase) != hostOf(apkUrl)) return OriginRefusal.HOST_MISMATCH
        if (portOf(serverBase) != portOf(apkUrl)) return OriginRefusal.PORT_MISMATCH
        return null
    }

    /** Returns the refusal that [serverBase] alone gives under the origin rules, or null when the base may be requested. */
    internal fun refusalForBase(serverBase: String): OriginRefusal? = problemOf(serverBase)

    /** Returns the first rule that [url] breaks, or null when the address has an acceptable origin. */
    private fun problemOf(url: String): OriginRefusal? {
        if (!hasOnlyUrlChars(url)) return OriginRefusal.NOT_USABLE
        if (!url.regionMatches(0, PREFIX, 0, PREFIX.length, ignoreCase = true)) return OriginRefusal.NOT_HTTPS
        if (url.contains('#')) return OriginRefusal.FRAGMENT
        val authority = authorityOf(url)
        if (authority.contains('@')) return OriginRefusal.USER_INFO
        return if (isAuthorityUsable(authority)) null else OriginRefusal.NOT_USABLE
    }

    /** True when every character is printable ASCII and none is a backslash. */
    private fun hasOnlyUrlChars(url: String): Boolean = url.all { c -> c in '!'..'~' && c != '\\' }

    /** The text after the scheme, up to the first slash or question mark. */
    private fun authorityOf(url: String): String =
        url.substring(PREFIX.length).takeWhile { c -> c != '/' && c != '?' }

    /** True when the authority has at most one colon, a usable host and an optional usable port. */
    private fun isAuthorityUsable(authority: String): Boolean {
        val colons = authority.count { c -> c == ':' }
        if (colons > 1) return false
        if (colons == 1 && !isPortText(authority.substringAfter(':'))) return false
        val host = authority.substringBefore(':')
        return host.split('.').all { piece -> piece.isNotEmpty() && piece.all { c -> isHostChar(c) } }
    }

    private fun isHostChar(c: Char): Boolean =
        c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-'

    /** A port is 1 to 5 digits, with no leading zero, and a value from 1 to 65535. */
    private fun isPortText(text: String): Boolean =
        text.length in 1..5 && text.all { c -> c in '0'..'9' } && text[0] != '0' && text.toInt() in 1..65535

    private fun hostOf(url: String): String = authorityOf(url).substringBefore(':').lowercase()

    private fun portOf(url: String): Int {
        val authority = authorityOf(url)
        return if (authority.contains(':')) authority.substringAfter(':').toInt() else DEFAULT_PORT
    }
}
