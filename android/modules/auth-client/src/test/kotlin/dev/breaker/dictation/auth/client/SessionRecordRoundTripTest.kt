package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRecordRoundTripTest {
    private val server = "https://h.example"

    private fun readStored(session: AuthSession, address: String): StoredSession {
        val text = SessionRecord.encode(session, address)
        return SessionRecord.decode(text) ?: throw AssertionError("the encoded record was refused")
    }

    private fun assertKept(session: AuthSession, address: String) {
        val back = readStored(session, address)
        assertEquals("the session changed", session, back.session)
        assertEquals("the server changed", address, back.server)
    }

    @Test
    fun `a write and read back returns an equal admin session`() {
        val session = AuthSession("u-9", "root", "t-admin", UserRole.ADMIN, setOf(TokenScope.ADMIN, TokenScope.TRANSCRIBE))
        assertKept(session, server)
    }

    @Test
    fun `a write and read back returns an equal user session`() {
        val session = AuthSession("u-1", "alice", "t-user", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        assertKept(session, server)
    }

    @Test
    fun `a write and read back keeps quotes backslashes and control characters`() {
        val bearerText = "\"\\\u0001\u001F\t\n\r\u0008\u000C end"
        val session = AuthSession("u-1", "a \"quoted\" name", bearerText, UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        assertKept(session, server)
    }

    @Test
    fun `a write and read back keeps non-ASCII characters and a surrogate pair`() {
        val bearerText = "caf\u00E9 \u65E5 \uD83D\uDE00"
        val session = AuthSession("u-2", "b\u00FCrger", bearerText, UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        assertKept(session, server)
    }

    @Test
    fun `a write and read back keeps an empty scope set`() {
        val session = AuthSession("u-3", "carol", "t-empty", UserRole.USER, emptySet())
        assertKept(session, server)
    }

    @Test
    fun `a write and read back keeps a lone surrogate written as an escape`() {
        val session = AuthSession("u-4", "dave", "x\uD800y", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        assertKept(session, server)
    }

    @Test
    fun `a write and read back keeps a server with quotes backslashes and control characters`() {
        val address = "https://h.example/\"a\\b\u0001\u001F\t\n\r\u0008\u000C end"
        val session = AuthSession("u-1", "alice", "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        assertKept(session, address)
    }

    @Test
    fun `a write and read back keeps a server with non-ASCII characters and a surrogate pair`() {
        val address = "https://caf\u00E9.example/\u65E5/\uD83D\uDE00"
        val session = AuthSession("u-1", "alice", "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        assertKept(session, address)
    }

    @Test
    fun `a write and read back keeps a server exactly as given with no trim and no case change`() {
        val address = " HTTPS://H.Example// "
        val session = AuthSession("u-1", "alice", "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        assertKept(session, address)
    }

    @Test
    fun `the stored session prints only its type name`() {
        val session = AuthSession("u-1", "alice", "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        assertEquals("StoredSession", readStored(session, server).toString())
    }

    @Test
    fun `encode writes the documented key order and no spaces`() {
        val session = AuthSession("u-1", "alice", "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        assertEquals(
            "the documented shape",
            "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\"," +
                "\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}",
            SessionRecord.encode(session, server),
        )
    }

    @Test
    fun `encode ends the record with the server member for a plain server`() {
        val session = AuthSession("u-1", "alice", "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        val text = SessionRecord.encode(session, server)
        assertTrue("the record does not end with the server member", text.endsWith("\"server\":\"https://h.example\"}"))
    }

    @Test
    fun `encode writes the admin role and both scopes in enum order`() {
        val session = AuthSession("u-9", "root", "t", UserRole.ADMIN, setOf(TokenScope.ADMIN, TokenScope.TRANSCRIBE))
        assertEquals(
            "scopes follow the enum order, not the set order",
            "{\"user_id\":\"u-9\",\"username\":\"root\",\"token\":\"t\",\"role\":\"admin\"," +
                "\"scopes\":[\"transcribe\",\"admin\"],\"server\":\"https://h.example\"}",
            SessionRecord.encode(session, server),
        )
    }

    @Test
    fun `encode escapes quote backslash and control characters as the documented forms`() {
        val bearerText = "\"\\\u0001\u001F\t\n\r\u0008\u000C\u00E9\uD83D\uDE00"
        val session = AuthSession("u-1", "alice", bearerText, UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        val expectedText = "\\\"" + "\\\\" + "\\u0001" + "\\u001f" + "\\t" + "\\n" + "\\r" + "\\b" + "\\f" +
            "\u00E9" + "\uD83D\uDE00"
        assertEquals(
            "the escapes in the documented form, with non-ASCII and the pair written raw",
            "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"" + expectedText +
                "\",\"role\":\"user\",\"scopes\":[\"transcribe\"],\"server\":\"https://h.example\"}",
            SessionRecord.encode(session, server),
        )
    }

    @Test
    fun `encode escapes the server with the same string writer`() {
        val session = AuthSession("u-1", "alice", "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        val text = SessionRecord.encode(session, "a\"b\\c\u0001")
        assertTrue("the server was not escaped", text.endsWith("\"server\":\"a\\\"b\\\\c\\u0001\"}"))
    }

    @Test
    fun `encode with an empty server throws an illegal argument exception`() {
        val session = AuthSession("u-1", "alice", "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE))
        var thrown: Throwable? = null
        try {
            SessionRecord.encode(session, "")
        } catch (refused: IllegalArgumentException) {
            thrown = refused
        }
        assertTrue("an empty server was written", thrown != null)
    }
}
