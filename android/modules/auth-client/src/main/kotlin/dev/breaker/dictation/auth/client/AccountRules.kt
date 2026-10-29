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

    const val REGISTER_PATH = "/v1/auth/register"
    const val SALT_PATH = "/v1/auth/salt"
    const val LOGIN_PATH = "/v1/auth/login"
    const val LOGOUT_PATH = "/v1/auth/logout"

    private const val HTTPS_PREFIX = "https://"

    /**
     * True when [password] has at least [MIN_PASSWORD_CHARS] Unicode code points. The password is
     * counted as it is given, before the crypto module normalises it. Used at registration only;
     * login never applies this rule.
     */
    fun isLongEnough(password: String): Boolean =
        password.codePointCount(0, password.length) >= MIN_PASSWORD_CHARS

    /**
     * The URL for [path] under [base], or null when [base] is not usable as a server address.
     * The base must be https, and it may not hold a question mark or a hash. The authority, the
     * part between the scheme and the first slash, must not be empty, must not start with a colon
     * and must not hold an at sign, so a user name or a missing host is refused. Nothing after the
     * scheme may be whitespace or a control character. Every trailing slash on the base is removed
     * before the path is added.
     */
    fun endpoint(base: String, path: String): String? {
        if (!hasHttpsScheme(base)) return null
        if (base.contains('?') || base.contains('#')) return null
        val afterScheme = base.substring(HTTPS_PREFIX.length)
        val authority = afterScheme.substringBefore('/')
        if (authority.isEmpty()) return null
        if (authority.startsWith(':')) return null
        if (authority.contains('@')) return null
        if (afterScheme.any { it.isWhitespace() || Character.isISOControl(it) }) return null
        return base.trimEnd('/') + path
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
     * True when the start of [base] is the https scheme. Only ASCII letters fold to the scheme,
     * so a look-alike letter from another alphabet is refused.
     */
    private fun hasHttpsScheme(base: String): Boolean {
        if (base.length <= HTTPS_PREFIX.length) return false
        return base.substring(0, HTTPS_PREFIX.length).lowercase() == HTTPS_PREFIX
    }
}
