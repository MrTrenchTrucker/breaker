package dev.breaker.dictation.auth.client

/**
 * Says whether two server addresses name the same server. Pure: no state, no logging and no
 * trim. The texts are only normalised for the comparison; neither text is changed.
 *
 * Same server means: the scheme equal without ASCII case, the authority (host and port) equal
 * without ASCII case, and everything after the authority exactly equal. One trailing slash on
 * each text is ignored. A text without a scheme and an authority, with an empty host before the
 * port, or with user info, never matches. A text with a backslash, whitespace or a control
 * character never matches, and an empty text matches nothing.
 */
internal object ServerAddressMatch {
    private const val SCHEME_END = "://"

    /** True when [stored] and [current] are the same server address under the rules above. */
    fun same(stored: String, current: String): Boolean {
        if (stored.isEmpty() || current.isEmpty()) return false
        if (unusable(stored) || unusable(current)) return false
        val first = split(dropOneSlash(stored)) ?: return false
        val second = split(dropOneSlash(current)) ?: return false
        return equalIgnoringAsciiCase(first.scheme, second.scheme) &&
            equalIgnoringAsciiCase(first.authority, second.authority) &&
            first.rest == second.rest
    }

    /** The three parts of an address: scheme, authority and the rest from the first slash on. */
    private class Parts(val scheme: String, val authority: String, val rest: String)

    /** [text] without its last character when that is a slash; only one slash is ever dropped. */
    internal fun dropOneSlash(text: String): String =
        if (text.endsWith('/')) text.substring(0, text.length - 1) else text

    /** True when [text] holds a backslash, whitespace or a control character. */
    private fun unusable(text: String): Boolean =
        text.any { it == '\\' || it.isWhitespace() || Character.isISOControl(it) }

    private fun split(text: String): Parts? {
        val at = text.indexOf(SCHEME_END)
        if (at <= 0) return null
        val afterScheme = at + SCHEME_END.length
        var end = afterScheme
        while (end < text.length && text[end] != '/' && text[end] != '?' && text[end] != '#') {
            end++
        }
        if (end == afterScheme) return null
        val authority = text.substring(afterScheme, end)
        if (authority.contains('@')) return null
        if (authority.substringBefore(':').isEmpty()) return null
        return Parts(text.substring(0, at), authority, text.substring(end))
    }

    private fun equalIgnoringAsciiCase(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        for (i in a.indices) {
            if (foldAscii(a[i]) != foldAscii(b[i])) return false
        }
        return true
    }

    /** Folds only a to z and A to Z; every other character is returned as it is. */
    private fun foldAscii(c: Char): Char = if (c in 'A'..'Z') c + ('a' - 'A') else c
}
