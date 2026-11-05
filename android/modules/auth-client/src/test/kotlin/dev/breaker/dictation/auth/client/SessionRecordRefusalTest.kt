package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE =
    "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"]," +
        "\"server\":\"https://h.example\"}"

class SessionRecordRefusalTest {

    /** Returns the base record with one piece replaced; fails if the piece is not in the base. */
    private fun edit(old: String, new: String): String {
        assertTrue("the edit target must be in the base record: " + old, BASE.contains(old))
        return BASE.replace(old, new)
    }

    private fun refuseAll(label: String, texts: List<String>) {
        for (text in texts) {
            assertNull(label, SessionRecord.decode(text))
        }
    }

    @Test
    fun `decode refuses text that is empty or only whitespace`() {
        refuseAll("empty or blank text", listOf("", " "))
    }

    @Test
    fun `decode refuses text that is not a JSON object`() {
        refuseAll("not an object", listOf("[]", "\"text\"", "null", "42"))
    }

    @Test
    fun `decode refuses a missing key`() {
        refuseAll(
            "missing key",
            listOf(
                edit(",\"scopes\":[\"transcribe\"]", ""),
                "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"scopes\":[\"transcribe\"]," +
                    "\"extra\":\"x\",\"server\":\"https://h.example\"}",
            ),
        )
    }

    @Test
    fun `decode refuses an extra key`() {
        refuseAll(
            "extra key",
            listOf(
                edit("\"server\":\"https://h.example\"}", "\"server\":\"https://h.example\",\"extra\":\"x\"}"),
                edit("[\"transcribe\"],", "[\"transcribe\"],\"extra\":\"x\","),
            ),
        )
    }

    @Test
    fun `decode refuses a repeated key`() {
        refuseAll("repeated key", listOf(edit("\"username\":\"alice\"", "\"username\":\"alice\",\"username\":\"bob\"")))
    }

    @Test
    fun `decode refuses a value of the wrong type`() {
        refuseAll("wrong type", listOf(edit("\"role\":\"user\"", "\"role\":1"), edit("\"username\":\"alice\"", "\"username\":true")))
    }

    @Test
    fun `decode refuses an unknown role name`() {
        refuseAll("unknown role", listOf(edit("\"role\":\"user\"", "\"role\":\"root\""), edit("\"role\":\"user\"", "\"role\":\"ADMIN\"")))
    }

    @Test
    fun `decode refuses an unknown or differently cased scope name`() {
        refuseAll("unknown scope", listOf(edit("[\"transcribe\"]", "[\"write\"]"), edit("[\"transcribe\"]", "[\"TRANSCRIBE\"]")))
    }

    @Test
    fun `decode refuses a repeated scope name`() {
        refuseAll("repeated scope", listOf(edit("[\"transcribe\"]", "[\"transcribe\",\"transcribe\"]")))
    }

    @Test
    fun `decode refuses a blank token because the session refuses it`() {
        refuseAll("blank token", listOf(edit("\"token\":\"tok\"", "\"token\":\"  \"")))
    }

    @Test
    fun `decode refuses text after the object`() {
        refuseAll("text after the object", listOf(BASE + "x", BASE + BASE))
    }

    @Test
    fun `decode refuses a trailing comma in the object or the array`() {
        refuseAll(
            "trailing comma",
            listOf(
                edit("\"https://h.example\"}", "\"https://h.example\",}"),
                edit("[\"transcribe\"]", "[\"transcribe\",]"),
            ),
        )
    }

    @Test
    fun `decode refuses JSON whitespace between tokens and after the object`() {
        refuseAll(
            "whitespace",
            listOf(edit("\"user_id\":", "\"user_id\": "), BASE + "\n"),
        )
    }

    @Test
    fun `decode accepts an empty scope array`() {
        val text = edit("[\"transcribe\"]", "[]")
        val stored = SessionRecord.decode(text) ?: throw AssertionError("an empty scope set was refused")
        assertEquals(
            "an empty scope set is a valid session",
            AuthSession("u-1", "alice", "tok", UserRole.USER, emptySet()),
            stored.session,
        )
        assertEquals("the server changed", "https://h.example", stored.server)
    }

    @Test
    fun `decode accepts the six keys in any order`() {
        val text = "{\"server\":\"https://h.example\",\"scopes\":[\"transcribe\"],\"role\":\"user\"," +
            "\"token\":\"tok\",\"username\":\"alice\",\"user_id\":\"u-1\"}"
        val stored = SessionRecord.decode(text) ?: throw AssertionError("a reordered record was refused")
        assertEquals(
            "key order does not matter on decode",
            AuthSession("u-1", "alice", "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE)),
            stored.session,
        )
        assertEquals("the server changed", "https://h.example", stored.server)
    }

    @Test
    fun `decode refuses an escaped spelling of a key`() {
        refuseAll("escaped key", listOf(edit("\"user_id\"", "\"us\\u0065r_id\"")))
    }

    @Test
    fun `decode refuses a record with no server`() {
        refuseAll("missing server", listOf(edit(",\"server\":\"https://h.example\"", "")))
    }

    @Test
    fun `decode refuses an empty server`() {
        refuseAll("empty server", listOf(edit("\"server\":\"https://h.example\"", "\"server\":\"\"")))
    }

    @Test
    fun `decode refuses a server that is not a text string`() {
        val old = "\"server\":\"https://h.example\""
        refuseAll(
            "server of the wrong type",
            listOf(
                edit(old, "\"server\":[\"https://h.example\"]"),
                edit(old, "\"server\":{}"),
                edit(old, "\"server\":1"),
                edit(old, "\"server\":null"),
                edit(old, "\"server\":true"),
            ),
        )
    }

    @Test
    fun `decode refuses a server holding a raw control character`() {
        refuseAll("raw control in server", listOf(edit("h.example", "h\u0001.example")))
    }

    @Test
    fun `decode refuses a repeated server key`() {
        refuseAll(
            "repeated server",
            listOf(edit("\"server\":\"https://h.example\"", "\"server\":\"https://h.example\",\"server\":\"https://o.example\"")),
        )
    }

    @Test
    fun `decode refuses a seventh member after the server`() {
        refuseAll(
            "seventh member",
            listOf(edit("\"server\":\"https://h.example\"}", "\"server\":\"https://h.example\",\"more\":\"x\"}")),
        )
    }
}
