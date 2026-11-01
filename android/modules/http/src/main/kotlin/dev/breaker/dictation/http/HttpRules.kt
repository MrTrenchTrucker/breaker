package dev.breaker.dictation.http

/**
 * The checks that run before a connection is opened, and before a body byte is read. Each refusal is an
 * HttpRefusal with a fixed reason; no check copies the value it refuses into a message.
 *
 * An address must pass [ServerAddressRule]. An https address passes for any host when its scheme is ASCII
 * and equals https ignoring ASCII case only, it holds no whitespace and no control character, and its host
 * is non-empty and ASCII. A host with a non-ASCII character is refused, because converting a host to
 * punycode is the caller's job. An at sign in the authority, which is the text before the first slash,
 * question mark or hash, is refused as well. A plain http address passes only when it names the configured
 * server; any other plain http address is refused.
 */
internal object HttpRules {
    private const val SCHEME_SEPARATOR = "://"
    private const val HTTPS_SCHEME = "https"
    private const val ASCII_CHARS = 128

    /** Refuses an address the rule does not permit for [configuredServer], then a header that is not usable. */
    fun checkBeforeConnect(request: CallRequest, configuredServer: String) {
        if (!ServerAddressRule.permits(request.url, configuredServer)) throw HttpRefusal(HttpRefusalReason.NOT_HTTPS)
        for ((name, value) in request.headers) {
            if (name.isEmpty() || hasLineBreak(name) || hasLineBreak(value)) {
                throw HttpRefusal(HttpRefusalReason.BAD_HEADER)
            }
        }
    }

    /** Refuses an announced reply length above the cap, before any body byte is read. */
    fun checkAnnouncedLength(announced: Long, cap: Long) {
        if (announced > cap) throw HttpRefusal(HttpRefusalReason.REPLY_TOO_LARGE)
    }

    /** True when [url] is an https address the module connects to. It is the https branch of [ServerAddressRule]. */
    fun isHttpsAddress(url: String): Boolean {
        if (url.any { it.isWhitespace() || Character.isISOControl(it) }) return false
        val separator = url.indexOf(SCHEME_SEPARATOR)
        if (separator < 0) return false
        val scheme = url.substring(0, separator)
        if (!isAsciiHttps(scheme)) return false
        val authority = authorityOf(url.substring(separator + SCHEME_SEPARATOR.length))
        if (authority.contains('@')) return false
        val host = hostOf(authority)
        return host.isNotEmpty() && host.all { it.code < ASCII_CHARS }
    }

    private fun isAsciiHttps(scheme: String): Boolean =
        scheme.all { it.code < ASCII_CHARS } && scheme.equals(HTTPS_SCHEME, ignoreCase = true)

    /** The authority of an address after its scheme: the text up to the first slash, question mark or hash. */
    private fun authorityOf(afterScheme: String): String =
        afterScheme.takeWhile { it != '/' && it != '?' && it != '#' }

    /** The host of an authority with no user information: the text before the port, or a bracketed IPv6 address. */
    private fun hostOf(authority: String): String {
        if (!authority.startsWith('[')) return authority.substringBefore(':')
        val close = authority.indexOf(']')
        return if (close < 0) "" else authority.substring(0, close + 1)
    }

    private fun hasLineBreak(text: String): Boolean = text.any { it == '\r' || it == '\n' }
}
