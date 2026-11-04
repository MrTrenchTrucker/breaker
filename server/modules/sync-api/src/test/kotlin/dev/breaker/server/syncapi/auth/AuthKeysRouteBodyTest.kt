package dev.breaker.server.syncapi.auth

import io.ktor.http.HttpStatusCode
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AuthKeysRouteBodyTest : AuthKeysRouteTestBase() {

    private fun quoted(text: String): String = "\"$text\""

    private fun members(): List<Pair<String, String>> = listOf(
        "wrapped_dek" to quoted(wrappedDekText),
        "wrapped_box_privkey" to quoted(wrappedBoxPrivkeyText),
        "box_pubkey" to quoted(boxPubkeyText),
    )

    private fun objectOf(values: List<Pair<String, String>>): String =
        values.joinToString(",", "{", "}") { (name, raw) -> "\"$name\":$raw" }

    /** The valid body with the text of member [member] (0 dek, 1 private key, 2 public key) replaced. */
    private fun withMember(member: Int, text: String): String {
        val texts = mutableListOf(wrappedDekText, wrappedBoxPrivkeyText, boxPubkeyText)
        texts[member] = text
        return bodyOf(texts[0], texts[1], texts[2])
    }

    /** The valid body with the raw JSON value of the member called [name] replaced. */
    private fun withRaw(name: String, raw: String): String = objectOf(members().map { (key, value) -> key to (if (key == name) raw else value) })

    private fun assertBadRequest(session: String, label: String, body: String) {
        val reply = postKeys(session, body)
        assertEquals("sync-api: $label status", HttpStatusCode.BadRequest, reply.status)
        assertEquals("sync-api: $label body", badRequestBody, reply.body)
    }

    private fun assertWrongSizes(member: Int, sizes: List<Int>) {
        val id = register("alice", 1)
        val session = sessionToken(id)
        for (size in sizes) {
            val text = KeyPatterns.text(ByteArray(size) { i -> (i + 9).toByte() })
            assertBadRequest(session, "member $member with $size bytes", withMember(member, text))
        }
        assertKeyless(id, "a post with a wrong size")
    }

    @Test
    fun `a wrapped_dek of 59 or 61 bytes gets 400 and nothing is written`() {
        assertWrongSizes(0, listOf(59, 61))
    }

    @Test
    fun `a wrapped_box_privkey of 59 or 61 bytes gets 400 and nothing is written`() {
        assertWrongSizes(1, listOf(59, 61))
    }

    @Test
    fun `a box_pubkey of 59 or 61 bytes gets 400 and nothing is written`() {
        assertWrongSizes(2, listOf(59, 61))
    }

    @Test
    fun `a box_pubkey of 31 or 33 bytes has the right text length and still gets 400`() {
        assertWrongSizes(2, listOf(31, 33))
    }

    @Test
    fun `a wrapped_dek of 59 bytes has the right text length and still gets 400`() {
        val text = KeyPatterns.text(ByteArray(59) { i -> (i + 9).toByte() })
        assertEquals("sync-api: the 59 byte text must have the length of a valid one", 80, text.length)
        assertWrongSizes(0, listOf(59))
    }

    @Test
    fun `values that are not base64 get 400 and nothing is written`() {
        val id = register("alice", 1)
        val session = sessionToken(id)
        assertBadRequest(session, "dek of stars", bodyOf("*".repeat(80), wrappedBoxPrivkeyText, boxPubkeyText))
        assertBadRequest(session, "private key of stars", bodyOf(wrappedDekText, "*".repeat(80), boxPubkeyText))
        assertBadRequest(session, "public key of stars", bodyOf(wrappedDekText, wrappedBoxPrivkeyText, "*".repeat(44)))
        assertKeyless(id, "a post with values that are not base64")
    }

    @Test
    fun `url-safe base64 of the right length gets 400 for each member`() {
        val id = register("alice", 1)
        val session = sessionToken(id)
        val dek = Base64.getUrlEncoder().encodeToString(ByteArray(60) { (-5).toByte() })
        val pubkey = Base64.getUrlEncoder().encodeToString(ByteArray(32) { (-5).toByte() })
        assertTrue("sync-api: the test text must use url-safe characters: $dek", dek.contains("-") && dek.contains("_"))
        assertEquals("sync-api: the url-safe dek must have the valid length", 80, dek.length)
        assertEquals("sync-api: the url-safe public key must have the valid length", 44, pubkey.length)
        assertBadRequest(session, "url-safe dek", bodyOf(dek, wrappedBoxPrivkeyText, boxPubkeyText))
        assertBadRequest(session, "url-safe private key", bodyOf(wrappedDekText, dek, boxPubkeyText))
        assertBadRequest(session, "url-safe public key", bodyOf(wrappedDekText, wrappedBoxPrivkeyText, pubkey))
        assertKeyless(id, "a post with url-safe values")
    }

    @Test
    fun `whitespace inside a value gets 400 whether it is a space or a JSON newline`() {
        val id = register("alice", 1)
        val session = sessionToken(id)
        val spaced = wrappedDekText.substring(0, 10) + " " + wrappedDekText.substring(11)
        val broken = wrappedDekText.substring(0, 10) + "\\n" + wrappedDekText.substring(11)
        assertBadRequest(session, "dek with a space", bodyOf(spaced, wrappedBoxPrivkeyText, boxPubkeyText))
        assertBadRequest(session, "dek with a newline", bodyOf(broken, wrappedBoxPrivkeyText, boxPubkeyText))
        assertBadRequest(session, "public key with a space", bodyOf(wrappedDekText, wrappedBoxPrivkeyText, " " + boxPubkeyText.drop(1)))
        assertKeyless(id, "a post with whitespace")
    }

    @Test
    fun `an unpadded or wrongly padded box_pubkey gets 400`() {
        val id = register("alice", 1)
        val session = sessionToken(id)
        assertEquals("sync-api: the valid public key text ends in one padding character", '=', boxPubkeyText.last())
        assertBadRequest(session, "unpadded public key", withMember(2, boxPubkeyText.dropLast(1)))
        assertBadRequest(session, "public key padded with a letter", withMember(2, boxPubkeyText.dropLast(1) + "A"))
        assertKeyless(id, "a post with a wrongly padded value")
    }

    @Test
    fun `a box_pubkey of 43 valid characters and one illegal 44th character gets 400`() {
        val id = register("alice", 1)
        val session = sessionToken(id)
        val head = boxPubkeyText.take(43)
        assertEquals("sync-api: the valid part of the public key text has 43 characters", 43, head.length)
        // A lenient decoder skips the illegal 44th character and reads 43 characters as unpadded
        // base64 of 32 bytes; only a 44-character value tells it apart from the strict decoder.
        assertBadRequest(session, "public key with a newline as 44th character", withMember(2, head + "\\n"))
        assertBadRequest(session, "public key with a space as 44th character", withMember(2, head + " "))
        assertBadRequest(session, "public key with an exclamation mark as 44th character", withMember(2, head + "!"))
        assertKeyless(id, "a post with an illegal 44th character")
    }

    @Test
    fun `malformed JSON and bodies that are not an object get 400`() {
        val id = register("alice", 1)
        val session = sessionToken(id)
        val cases = listOf(
            "truncated" to """{"wrapped_dek":""",
            "not json" to "{ not json",
            "array" to "[]",
            "string" to "\"text\"",
            "number" to "12345",
            "empty body" to "",
            "empty object" to "{}",
            "trailing comma" to validBody().dropLast(1) + ",}",
        )
        for ((label, body) in cases) {
            assertBadRequest(session, label, body)
        }
        assertKeyless(id, "a post with a malformed body")
    }

    @Test
    fun `each missing member gets 400 and nothing is written`() {
        val id = register("alice", 1)
        val session = sessionToken(id)
        for ((name, _) in members()) {
            assertBadRequest(session, "body without $name", objectOf(members().filter { (key, _) -> key != name }))
        }
        assertKeyless(id, "a post with a missing member")
    }

    @Test
    fun `an extra member and a duplicate member get 400 and nothing is written`() {
        val id = register("alice", 1)
        val session = sessionToken(id)
        assertBadRequest(session, "extra member", objectOf(members() + ("extra" to "\"x\"")))
        assertBadRequest(session, "duplicate member", objectOf(members() + members().first()))
        assertKeyless(id, "a post with an extra or duplicate member")
    }

    @Test
    fun `null number and array values get 400 for each member`() {
        val id = register("alice", 1)
        val session = sessionToken(id)
        for ((name, _) in members()) {
            for (raw in listOf("null", "12", "[]")) {
                assertBadRequest(session, "$name as $raw", withRaw(name, raw))
            }
        }
        assertKeyless(id, "a post with a value of the wrong type")
    }

    @Test
    fun `a body of 4097 bytes with a declared length gets 413 with the exact body`() {
        val id = register("alice", 1)
        val reply = postKeys(sessionToken(id), padTo(validBody(), 4097))
        assertEquals("sync-api: status", HttpStatusCode.PayloadTooLarge, reply.status)
        assertEquals("sync-api: body", tooLargeBody, reply.body)
        assertKeyless(id, "a body over the cap")
    }

    @Test
    fun `a body of exactly 4096 bytes with a declared length gets 200`() {
        val id = register("alice", 1)
        val reply = postKeys(sessionToken(id), padTo(validBody(), 4096))
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: body", """{"key_version":1}""", reply.body)
        assertStored(id, 1, "the body at the cap")
    }

    @Test
    fun `a chunked body of 4097 bytes gets 413 with the exact body`() {
        val id = register("alice", 1)
        val reply = postKeysChunked(sessionToken(id), padTo(validBody(), 4097).toByteArray())
        assertEquals("sync-api: status", HttpStatusCode.PayloadTooLarge, reply.status)
        assertEquals("sync-api: body", tooLargeBody, reply.body)
        assertKeyless(id, "a chunked body over the cap")
    }

    @Test
    fun `a chunked body of exactly 4096 bytes gets 200`() {
        val id = register("alice", 1)
        val reply = postKeysChunked(sessionToken(id), padTo(validBody(), 4096).toByteArray())
        assertEquals("sync-api: status", HttpStatusCode.OK, reply.status)
        assertEquals("sync-api: body", """{"key_version":1}""", reply.body)
        assertStored(id, 1, "the chunked body at the cap")
    }

    @Test
    fun `a keyed account with a wrong length body gets 400 and not 409`() {
        val id = register("alice", 1)
        val session = sessionToken(id)
        assertEquals("sync-api: first post", HttpStatusCode.OK, postKeys(session, validBody()).status)
        assertBadRequest(session, "wrong length from a keyed account", withMember(0, KeyPatterns.text(ByteArray(59) { 9 })))
        assertStored(id, 1, "the refused post")
    }

    @Test
    fun `a keyed account with an oversized body gets 413 and not 409`() {
        val id = register("alice", 1)
        val session = sessionToken(id)
        assertEquals("sync-api: first post", HttpStatusCode.OK, postKeys(session, validBody()).status)
        val reply = postKeys(session, padTo(validBody(), 4097))
        assertEquals("sync-api: status", HttpStatusCode.PayloadTooLarge, reply.status)
        assertEquals("sync-api: body", tooLargeBody, reply.body)
        assertStored(id, 1, "the oversized post")
    }
}
