package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.UserRole

/**
 * What the hand-off exchange answered, read and checked: the session, the version of the key
 * set and the sealed key package bytes. It is a plain class, and [toString] prints no part of
 * it. The caller owns [sealedBytes] and zeroes them when done.
 */
internal class ExchangeAnswer(val session: AuthSession, val keyVersion: Int, val sealedBytes: ByteArray) {
    override fun toString(): String = "ExchangeAnswer(redacted)"
}

/**
 * Reads the answer of the hand-off exchange with the strict reader of this module. The body
 * must be one object. Unknown extra members are ignored. Every refusal is an
 * [AuthDecodeException] with a fixed message that carries no value from the body.
 */
internal object ExchangeDecoder {
    private const val SESSION_TEXT_CHARS = 43

    /**
     * The answer in [body], or an [AuthDecodeException]. The members are token (43 URL-safe
     * characters), role (user or admin), key_version (0 up to the largest Int), sealed_package
     * (standard base64, not empty), username (not blank) and account_id (an integer, checked and
     * not kept). The session uses the username as its user id, as a password sign-in does.
     */
    fun parseExchange(body: String): ExchangeAnswer {
        val members = membersOf(body)
        val sessionText = textOf(members, "token")
        if (!hasSessionShape(sessionText)) throw AuthDecodeException("BAD_VALUE", "token has the wrong shape")
        val role = roleOf(textOf(members, "role"))
        val keyVersion = versionOf(numberOf(members, "key_version"))
        val packageText = textOf(members, "sealed_package")
        val username = textOf(members, "username")
        if (username.isBlank()) throw AuthDecodeException("BAD_VALUE", "username is blank")
        numberOf(members, "account_id")
        val sealedBytes = try {
            java.util.Base64.getDecoder().decode(packageText)
        } catch (e: IllegalArgumentException) {
            throw AuthDecodeException("BAD_BASE64", "sealed_package is not valid base64")
        }
        if (sealedBytes.isEmpty()) throw AuthDecodeException("BAD_VALUE", "sealed_package is empty")
        val session = AuthSession(username, username, sessionText, role, AccountRules.scopesFor(role))
        return ExchangeAnswer(session, keyVersion, sealedBytes)
    }

    /**
     * The session text of [body] when it parses as one object whose member token is a 43
     * character URL-safe text, else null. Never throws.
     */
    fun tokenShapeIfPresent(body: String): String? =
        try {
            val found = (membersOf(body)["token"] as? JsonValue.Str)?.s
            if (found != null && hasSessionShape(found)) found else null
        } catch (e: Exception) {
            null
        }

    private fun membersOf(body: String): Map<String, JsonValue> {
        val value = JsonValue.parse(body)
        return when (value) {
            is JsonValue.Obj -> value.map
            else -> throw AuthDecodeException("TYPE", "expected a JSON object body")
        }
    }

    private fun textOf(members: Map<String, JsonValue>, key: String): String {
        val v = members[key] ?: throw AuthDecodeException("MISSING_KEY", "required key '$key' missing")
        return if (v is JsonValue.Str) v.s else throw AuthDecodeException("TYPE", "key '$key' not a string")
    }

    private fun numberOf(members: Map<String, JsonValue>, key: String): Long {
        val v = members[key] ?: throw AuthDecodeException("MISSING_KEY", "required key '$key' missing")
        return if (v is JsonValue.Num) v.l else throw AuthDecodeException("TYPE", "key '$key' not an integer")
    }

    private fun roleOf(name: String): UserRole = when (name) {
        "user" -> UserRole.USER
        "admin" -> UserRole.ADMIN
        else -> throw AuthDecodeException("BAD_VALUE", "role is not user or admin")
    }

    private fun versionOf(wire: Long): Int =
        if (wire in 0L..Int.MAX_VALUE.toLong()) wire.toInt()
        else throw AuthDecodeException("BAD_VALUE", "key_version is out of range")

    private fun hasSessionShape(text: String): Boolean {
        if (text.length != SESSION_TEXT_CHARS) return false
        for (ch in text) {
            val allowed = (ch in 'a'..'z') || (ch in 'A'..'Z') || (ch in '0'..'9') || ch == '-' || ch == '_'
            if (!allowed) return false
        }
        return true
    }
}
