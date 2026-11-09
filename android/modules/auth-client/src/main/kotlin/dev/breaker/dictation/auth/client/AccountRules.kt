package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import java.security.SecureRandom

/**
 * The fixed numbers and rules of the account calls. Every function here is pure: no network,
 * no keys and no storage.
 */
internal object AccountRules {
    const val MEMORY_KIB = 65536
    const val ITERATIONS = 3
    const val PARALLELISM = 1
    const val KDF_VERSION = 1
    const val OUTPUT_LENGTH = 32
    const val SALT_BYTES = 16
    const val MIN_PASSWORD_CHARS = 12
    const val MAX_USERNAME_CHARS = 64

    const val REGISTER_PATH = "/v1/auth/register"
    const val SALT_PATH = "/v1/auth/salt"
    const val LOGIN_PATH = "/v1/auth/login"
    const val LOGOUT_PATH = "/v1/auth/logout"
    const val EXCHANGE_PATH = "/v1/auth/handoff/exchange"
    const val DEVICES_PATH = "/v1/auth/devices"

    private const val HTTPS_PREFIX = "https://"
    private const val HTTP_PREFIX = "http://"

    /**
     * True when [password] has at least [MIN_PASSWORD_CHARS] Unicode code points. The password is
     * counted as it is given, before the crypto module normalises it. Used at registration only;
     * login never applies this rule.
     */
    fun isLongEnough(password: CharArray): Boolean =
        Character.codePointCount(password, 0, password.size) >= MIN_PASSWORD_CHARS

    /**
     * True when [name] is a valid username: 1 to [MAX_USERNAME_CHARS] UTF-16 units, each one of
     * a to z, A to Z, 0 to 9, a dot, an underscore or a hyphen. Plain comparisons, no regular
     * expression, so a trailing newline or a non-ASCII letter never passes.
     */
    fun isValidUsername(name: String): Boolean {
        if (name.length < 1 || name.length > MAX_USERNAME_CHARS) return false
        for (ch in name) {
            val allowed = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') ||
                ch == '.' || ch == '_' || ch == '-'
            if (!allowed) return false
        }
        return true
    }

    /**
     * The URL for [path] under [base], or null when [base] is not usable as a server address.
     * The base must be http or https (ADR-034), and it may not hold a backslash, a question mark
     * or a hash. The authority, the part between the scheme and the first slash, must not be
     * empty, must not start with a colon and must not hold an at sign, so a user name or a missing
     * host is refused. Nothing after the scheme may be whitespace or a control character. One
     * trailing slash on the base is removed before the path is added; a base that still ends in
     * a slash after that is refused.
     */
    fun endpoint(base: String, path: String): String? {
        val prefixLength = schemeLength(base)
        if (prefixLength == 0) return null
        if (base.contains('\\')) return null
        if (base.contains('?') || base.contains('#')) return null
        val afterScheme = base.substring(prefixLength)
        val authority = afterScheme.substringBefore('/')
        if (authority.isEmpty()) return null
        if (authority.startsWith(':')) return null
        if (authority.contains('@')) return null
        if (afterScheme.any { it.isWhitespace() || Character.isISOControl(it) }) return null
        val trimmed = ServerAddressMatch.dropOneSlash(base)
        if (trimmed.endsWith('/')) return null
        return trimmed + path
    }

    /** [SALT_BYTES] random bytes from a secure random source, for the salt of a new account. */
    fun secureSalt(): ByteArray {
        val bytes = ByteArray(SALT_BYTES)
        SecureRandom().nextBytes(bytes)
        return bytes
    }

    /**
     * The scopes a token of [role] carries, kept as the phone's display copy only. The server
     * decides what the account may do. This function is the one place that copy is made.
     */
    fun scopesFor(role: UserRole): Set<TokenScope> = when (role) {
        UserRole.USER -> setOf(TokenScope.TRANSCRIBE)
        UserRole.ADMIN -> setOf(TokenScope.TRANSCRIBE, TokenScope.ADMIN)
    }

    /**
     * The length of the scheme prefix at the start of [base] (https or http), or 0 when [base]
     * starts with neither. Only ASCII letters fold to the scheme, so a look-alike letter from
     * another alphabet is refused.
     */
    private fun schemeLength(base: String): Int {
        if (startsWithPrefix(base, HTTPS_PREFIX)) return HTTPS_PREFIX.length
        if (startsWithPrefix(base, HTTP_PREFIX)) return HTTP_PREFIX.length
        return 0
    }

    private fun startsWithPrefix(base: String, prefix: String): Boolean {
        if (base.length <= prefix.length) return false
        return base.substring(0, prefix.length).lowercase() == prefix
    }
}
