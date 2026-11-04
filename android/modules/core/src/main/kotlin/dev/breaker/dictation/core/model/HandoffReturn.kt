package dev.breaker.dictation.core.model

/**
 * What a link opened on the phone means for a sign-in: the one-time code the website sent back,
 * or nothing usable. Only the exact return link is a code; every other link is ignored.
 */
sealed interface HandoffReturn {
    /** The one-time code of the sign-in. Its toString never prints the code. */
    class Code(val code: String) : HandoffReturn {
        override fun toString(): String = "HandoffReturn.Code(redacted)"
    }

    /** A link that is not the handoff return. It changes nothing. */
    data object Ignored : HandoffReturn
}

private const val RETURN_PREFIX = "breaker://handoff?code="
private const val CODE_LENGTH = 43
/** True for the 64 URL-safe characters: ASCII letters, digits, dash and underscore. */
private fun isUrlSafe(c: Char): Boolean = c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_'

/**
 * Reads a link that was opened on the phone. The whole string must be the exact prefix followed by
 * exactly 43 URL-safe characters and nothing after them; anything else is [HandoffReturn.Ignored].
 * The check is a plain string check: the link is not parsed as a URI.
 */
fun parseHandoffReturn(link: String?): HandoffReturn {
    if (link == null || !link.startsWith(RETURN_PREFIX)) return HandoffReturn.Ignored
    val code = link.substring(RETURN_PREFIX.length)
    if (code.length != CODE_LENGTH) return HandoffReturn.Ignored
    if (!code.all { isUrlSafe(it) }) return HandoffReturn.Ignored
    return HandoffReturn.Code(code)
}
