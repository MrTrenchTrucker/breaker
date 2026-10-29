package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private const val BASE =
    "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"role\":\"user\",\"scopes\":[\"transcribe\"]}"

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
                "{\"user_id\":\"u-1\",\"username\":\"alice\",\"token\":\"tok\",\"scopes\":[\"transcribe\"],\"extra\":\"x\"}",
            ),
        )
    }

    @Test
    fun `decode refuses an extra key`() {
        refuseAll("extra key", listOf(edit("\"scopes\":[\"transcribe\"]}", "\"scopes\":[\"transcribe\"],\"extra\":\"x\"}")))
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
                edit(",\"scopes\":[\"transcribe\"]}", ",\"scopes\":[\"transcribe\"],}"),
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
        assertEquals(
            "an empty scope set is a valid session",
            AuthSession("u-1", "alice", "tok", UserRole.USER, emptySet()),
            SessionRecord.decode(text),
        )
    }

    @Test
    fun `decode accepts the five keys in any order`() {
        val text = "{\"scopes\":[\"transcribe\"],\"role\":\"user\",\"token\":\"tok\",\"username\":\"alice\",\"user_id\":\"u-1\"}"
        assertEquals(
            "key order does not matter on decode",
            AuthSession("u-1", "alice", "tok", UserRole.USER, setOf(TokenScope.TRANSCRIBE)),
            SessionRecord.decode(text),
        )
    }

    @Test
    fun `decode refuses an escaped spelling of a key`() {
        refuseAll("escaped key", listOf(edit("\"user_id\"", "\"us\\u0065r_id\"")))
    }
}
