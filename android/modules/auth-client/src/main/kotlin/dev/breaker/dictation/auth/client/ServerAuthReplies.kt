package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.UserRole
import dev.breaker.shared.api.LoginResponse
import dev.breaker.shared.api.SaltResponse

/**
 * Maps the status and the body of the salt and login replies to the service's results. A reply
 * that cannot be parsed is an unreadable reply; a refusal from the key-derivation guard is not
 * caught here and reaches the caller unchanged.
 */
internal object ServerAuthReplies {
    /** The salt reply: 200 is parsed, 404 means no such account, any other status is a server error. */
    fun salt(reply: AuthHttpReply): SaltResponse = when (reply.status) {
        200 -> parsedSalt(reply)
        404 -> throw AuthFailure(AuthFailureReason.NO_SUCH_ACCOUNT)
        else -> throw AuthFailure(AuthFailureReason.SERVER_ERROR)
    }

    /** The login reply: 200 is parsed, 401 means bad credentials, any other status is a server error. */
    fun login(reply: AuthHttpReply): LoginResponse = when (reply.status) {
        200 -> parsedLogin(reply)
        401 -> throw AuthFailure(AuthFailureReason.BAD_CREDENTIALS)
        else -> throw AuthFailure(AuthFailureReason.SERVER_ERROR)
    }

    /** The session for a parsed login reply. A blank token or an unknown role is an unreadable reply. */
    fun session(username: String, login: LoginResponse): AuthSession {
        if (login.token.isBlank()) throw AuthFailure(AuthFailureReason.UNREADABLE_REPLY)
        val role = roleOf(login.role)
        return AuthSession(username, username, login.token, role, AccountRules.scopesFor(role))
    }

    /** The device list reply: 200 is decoded, 401 means the token was refused, any other status
     * is a server error. */
    fun devices(reply: AuthHttpReply): List<DeviceRow> =
        when (reply.status) {
            200 -> decodedDevices(reply)
            401 -> throw AuthFailure(AuthFailureReason.BAD_CREDENTIALS)
            else -> throw AuthFailure(AuthFailureReason.SERVER_ERROR)
        }

    private fun decodedDevices(reply: AuthHttpReply): List<DeviceRow> =
        try {
            DeviceListDecoder.decode(text(reply))
        } catch (malformedList: AuthDecodeException) {
            throw AuthFailure(AuthFailureReason.UNREADABLE_REPLY)
        }

    private fun parsedSalt(reply: AuthHttpReply): SaltResponse =
        try {
            AuthDecoder.parseSalt(text(reply))
        } catch (malformedSalt: AuthDecodeException) {
            throw AuthFailure(AuthFailureReason.UNREADABLE_REPLY)
        }

    private fun parsedLogin(reply: AuthHttpReply): LoginResponse =
        try {
            AuthDecoder.parseLogin(text(reply))
        } catch (malformedLogin: AuthDecodeException) {
            throw AuthFailure(AuthFailureReason.UNREADABLE_REPLY)
        }

    private fun roleOf(name: String): UserRole = when (name) {
        "admin" -> UserRole.ADMIN
        "user" -> UserRole.USER
        else -> throw AuthFailure(AuthFailureReason.UNREADABLE_REPLY)
    }

    private fun text(reply: AuthHttpReply): String = String(reply.body, Charsets.UTF_8)
}
