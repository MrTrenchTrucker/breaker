package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Test

class SessionRecordRoundTripTest {

    private fun roundTrip(session: AuthSession): AuthSession? = SessionRecord.decode(SessionRecord.encode(session))

    @Test
    fun `a round trip returns an equal admin session`() {
        val session = AuthSession("u-9", "root", "t-admin", UserRole.ADMIN, setOf(TokenScope.ADMIN, TokenScope.TRANSCRIBE))
        assertEquals(session, roundTrip(session))
    }

    @Test
    fun `a round trip returns an equal user session`() {
        val session = AuthSession("u-1", "alice", "t-user", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        assertEquals(session, roundTrip(session))
    }

    @Test
    fun `a round trip keeps quotes backslashes and control characters`() {
        val token = "\"\\\u0001\u001F\t\n\r\u0008\u000C end"
        val session = AuthSession("u-1", "a \"quoted\" name", token, UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        assertEquals(session, roundTrip(session))
    }

    @Test
    fun `a round trip keeps non-ASCII characters and a surrogate pair`() {
        val token = "caf\u00E9 \u65E5 \uD83D\uDE00"
        val session = AuthSession("u-2", "b\u00FCrger", token, UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        assertEquals(session, roundTrip(session))
    }

    @Test
    fun `a round trip keeps an empty scope set`() {
        val session = AuthSession("u-3", "carol", "t-empty", UserRole.USER, emptySet())
        assertEquals(session, roundTrip(session))
    }

    @Test
    fun `a round trip keeps a lone surrogate written as an escape`() {
        val session = AuthSession("u-4", "dave", "x\uD800y", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        assertEquals(session, roundTrip(session))
    }

    @Test
    fun `encode writes the documented key order and no spaces`() {
        val session = AuthSession("u-1", "alice", "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        assertEquals(
            "the documented shape",
            "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}",
            SessionRecord.encode(session),
        )
    }

    @Test
    fun `encode writes the admin role and both scopes in enum order`() {
        val session = AuthSession("u-9", "root", "t", UserRole.ADMIN, setOf(TokenScope.ADMIN, TokenScope.TRANSCRIBE))
        assertEquals(
            "scopes follow the enum order, not the set order",
            "{\"user_id\":\"u-9\",\"username\":\"root\",\"token\":\"t\",\"role\":\"admin\",\"scopes\":[\"transcribe\",\"admin\"]}",
            SessionRecord.encode(session),
        )
    }

    @Test
    fun `encode escapes quote backslash and control characters as the documented forms`() {
        val token = "\"\\\u0001\u001F\t\n\r\u0008\u000C\u00E9\uD83D\uDE00"
        val session = AuthSession("u-1", "alice", token, UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        val expectedToken = "\\\"" + "\\\\" + "\\u0001" + "\\u001f" + "\\t" + "\\n" + "\\r" + "\\b" + "\\f" +
            "\u00E9" + "\uD83D\uDE00"
        assertEquals(
            "the escapes in the documented form, with non-ASCII and the pair written raw",
            "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"" + expectedToken +
                "\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}",
            SessionRecord.encode(session),
        )
    }
}
