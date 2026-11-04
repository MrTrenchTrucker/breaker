package dev.breaker.dictation.core.model

import java.util.Base64

/**
 * The result of building the sign-in link: the link, or the one reason the server address
 * was refused. The link carries the challenge and the app key, so its text is never printed;
 * a refusal prints only its reason name.
 */
sealed interface SignInLinkResult {
    /** The link to open in the browser. Its toString never prints the link. */
    class Link(val url: String) : SignInLinkResult {
        override fun toString(): String = "SignInLinkResult.Link(redacted)"
    }

    /** The server address was refused for [reason]. */
    class Refused(val reason: SignInLinkRefusal) : SignInLinkResult {
        override fun toString(): String = "SignInLinkResult.Refused(${reason.name})"
    }
}

/** Why a server address cannot carry a sign-in link. */
enum class SignInLinkRefusal { NO_SERVER, NOT_HTTP, BAD_ADDRESS, HAS_QUERY_OR_FRAGMENT, HAS_USER_INFO }

/**
 * Builds the sign-in link for a server address and an attempt. The checks run in order and the
 * first one that fails is the reason. The link is the page on the server with the challenge and
 * the app key in its fragment, never in its query.
 *
 * A wiped attempt is a bug in the caller, so it throws instead of making a link.
 */
fun buildSignInLink(serverUrl: String, attempt: SignInAttempt): SignInLinkResult {
    val server = serverUrl.trim()
    if (server.isEmpty()) return refused(SignInLinkRefusal.NO_SERVER)
    if (server.any { it.isWhitespace() || it.isISOControl() }) return refused(SignInLinkRefusal.BAD_ADDRESS)
    val split = server.indexOf("://")
    if (split < 0) return refused(SignInLinkRefusal.NOT_HTTP)
    val scheme = server.substring(0, split).lowercase()
    if (scheme != "http" && scheme != "https") return refused(SignInLinkRefusal.NOT_HTTP)
    val rest = server.substring(split + 3)
    if (rest.contains('?') || rest.contains('#')) return refused(SignInLinkRefusal.HAS_QUERY_OR_FRAGMENT)
    val slash = rest.indexOf('/')
    val authority = if (slash < 0) rest else rest.substring(0, slash)
    val path = if (slash < 0) "" else rest.substring(slash)
    if (authority.contains('@')) return refused(SignInLinkRefusal.HAS_USER_INFO)
    if (authority.isEmpty() || authority.contains('[') || authority.contains(']')) return refused(SignInLinkRefusal.BAD_ADDRESS)
    if (!isValidHostAndPort(authority)) return refused(SignInLinkRefusal.BAD_ADDRESS)
    val prefixPath = path.trimEnd('/')
    if (!isValidPath(prefixPath)) return refused(SignInLinkRefusal.BAD_ADDRESS)
    if (attempt.isWiped) throw IllegalStateException("The sign-in attempt was wiped")
    val key = attempt.copyPublicKey()
    val encodedKey = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
    key.fill(0)
    return SignInLinkResult.Link("$scheme://$authority$prefixPath/signin.html#challenge=${attempt.challenge}&app_key=$encodedKey")
}

private fun refused(reason: SignInLinkRefusal): SignInLinkResult = SignInLinkResult.Refused(reason)

/** The host is non-empty and made of ASCII letters, digits, dots, dashes and underscores; an optional port is 1 to 5 digits. */
private fun isValidHostAndPort(authority: String): Boolean {
    val colon = authority.lastIndexOf(':')
    val host = if (colon < 0) authority else authority.substring(0, colon)
    if (colon >= 0) {
        val port = authority.substring(colon + 1)
        if (port.length !in 1..5 || !port.all { it in '0'..'9' }) return false
    }
    return host.isNotEmpty() && host.all { isHostCharacter(it) }
}

private fun isHostCharacter(c: Char): Boolean =
    c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '.' || c == '-' || c == '_'

/** An empty path is valid; otherwise no segment after the leading slash may be empty, one dot or two dots. */
private fun isValidPath(path: String): Boolean {
    if (path.isEmpty()) return true
    return path.substring(1).split('/').none { it.isEmpty() || it == "." || it == ".." }
}
