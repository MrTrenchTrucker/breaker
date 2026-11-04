package dev.breaker.server.syncapi.auth

import dev.breaker.server.syncapi.accounts.AccountFixtures
import dev.breaker.server.syncapi.accounts.KeyBundle
import dev.breaker.server.syncapi.accounts.StoreKeysResult
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class AuthLoginRouteKeysTest : AuthKeysRouteTestBase() {

    private val unauthorizedBody = """{"error":"unauthorized","message":"Invalid username or verifier"}"""

    private fun quoted(text: String): String = "\"$text\""

    private fun loginAs(name: String, seed: Int): Reply {
        val verifier = KeyPatterns.text(AccountFixtures.verifierBytes(seed))
        return postTo("/v1/auth/login", null, """{"username":"$name","auth_verifier":"$verifier"}""")
    }

    /** The raw JSON text of one top-level member: a quoted string, null or a number. */
    private fun memberOf(body: String, name: String): String {
        val marker = "\"$name\":"
        val start = body.indexOf(marker)
        check(start >= 0) { "sync-api test helper: member $name is missing from the body" }
        val from = start + marker.length
        var end = from
        while (end < body.length && body[end] != ',' && body[end] != '}') end++
        return body.substring(from, end)
    }

    private fun storeKeys(accountId: Long, keys: KeyBundle) {
        val result = runBlocking { accountStore.storeKeys(accountId, keys) }
        if (result !is StoreKeysResult.Stored) {
            throw AssertionError("sync-api: expected Stored in the set-up but got $result")
        }
    }

    private fun storeBase(accountId: Long) {
        storeKeys(accountId, KeyBundle(wrappedDekBytes, wrappedBoxPrivkeyBytes, boxPubkeyBytes))
    }

    private fun okBody(name: String, seed: Int): String {
        val reply = loginAs(name, seed)
        assertEquals("sync-api: login status", HttpStatusCode.OK, reply.status)
        return reply.body
    }

    @Test
    fun `login after storeKeys answers the three stored values as standard padded base64 and key_version 1`() {
        storeBase(register("alice", 1))
        val body = okBody("alice", 1)
        assertEquals("sync-api: wrapped_dek", quoted(wrappedDekText), memberOf(body, "wrapped_dek"))
        assertEquals("sync-api: wrapped_box_privkey", quoted(wrappedBoxPrivkeyText), memberOf(body, "wrapped_box_privkey"))
        assertEquals("sync-api: box_pubkey", quoted(boxPubkeyText), memberOf(body, "box_pubkey"))
        assertEquals("sync-api: key_version", "1", memberOf(body, "key_version"))
        assertEquals("sync-api: prev_wrapped_dek", "null", memberOf(body, "prev_wrapped_dek"))
        assertEquals("sync-api: reset_at", "null", memberOf(body, "reset_at"))
        assertEquals("sync-api: rekeyed_at", "null", memberOf(body, "rekeyed_at"))
        assertEquals("sync-api: role", quoted("admin"), memberOf(body, "role"))
        assertTrue("sync-api: token must be a string", memberOf(body, "token").startsWith("\""))
    }

    @Test
    fun `login answers the standard alphabet with plus slash and padding and not the url-safe one`() {
        val dek = ByteArray(60) { (-5).toByte() }
        val privkey = ByteArray(60) { (-5).toByte() }
        val pubkey = ByteArray(32) { (-5).toByte() }
        val dekText = KeyPatterns.text(dek)
        val pubkeyText = KeyPatterns.text(pubkey)
        assertTrue("sync-api: the test text must hold plus and slash: $dekText", dekText.contains("+/"))
        assertTrue("sync-api: the test public key text must be padded: $pubkeyText", pubkeyText.endsWith("="))
        storeKeys(register("alice", 1), KeyBundle(dek, privkey, pubkey))
        val body = okBody("alice", 1)
        assertEquals("sync-api: wrapped_dek", quoted(dekText), memberOf(body, "wrapped_dek"))
        assertEquals("sync-api: wrapped_box_privkey", quoted(dekText), memberOf(body, "wrapped_box_privkey"))
        assertEquals("sync-api: box_pubkey", quoted(pubkeyText), memberOf(body, "box_pubkey"))
    }

    @Test
    fun `login of a keyless account answers three nulls and key_version 0`() {
        register("alice", 1)
        val body = okBody("alice", 1)
        assertEquals("sync-api: wrapped_dek", "null", memberOf(body, "wrapped_dek"))
        assertEquals("sync-api: wrapped_box_privkey", "null", memberOf(body, "wrapped_box_privkey"))
        assertEquals("sync-api: box_pubkey", "null", memberOf(body, "box_pubkey"))
        assertEquals("sync-api: key_version", "0", memberOf(body, "key_version"))
    }

    @Test
    fun `login answers a key_version preset to 5`() {
        val id = register("alice", 1)
        storeBase(id)
        runSql(temp, "UPDATE accounts SET key_version = 5 WHERE id = $id")
        assertEquals("sync-api: key_version", "5", memberOf(okBody("alice", 1), "key_version"))
    }

    @Test
    fun `login answers a preset prev_wrapped_dek as base64`() {
        val id = register("alice", 1)
        storeBase(id)
        presetPrev(temp, id, KeyPatterns.prevWrappedDek())
        val body = okBody("alice", 1)
        assertEquals("sync-api: prev_wrapped_dek", quoted(KeyPatterns.text(KeyPatterns.prevWrappedDek())), memberOf(body, "prev_wrapped_dek"))
        assertEquals("sync-api: wrapped_dek", quoted(wrappedDekText), memberOf(body, "wrapped_dek"))
    }

    @Test
    fun `login answers preset rekeyed_at and reset_at as instants`() {
        val id = register("alice", 1)
        runSql(temp, "UPDATE accounts SET rekeyed_at = ${KeyPatterns.REKEYED_AT_MS}, reset_at = ${KeyPatterns.RESET_AT_MS} WHERE id = $id")
        val body = okBody("alice", 1)
        assertEquals("sync-api: rekeyed_at", quoted("2023-11-14T22:13:20Z"), memberOf(body, "rekeyed_at"))
        assertEquals("sync-api: reset_at", quoted("2023-11-14T22:13:20.123Z"), memberOf(body, "reset_at"))
    }

    @Test
    fun `two accounts each get their own keys at login`() {
        val alice = register("alice", 1)
        val bob = register("bob", 2)
        storeBase(alice)
        val bobDek = ByteArray(60) { i -> (i * 9 + 5).toByte() }
        val bobPrivkey = ByteArray(60) { i -> (i * 13 + 6).toByte() }
        val bobPubkey = ByteArray(32) { i -> (i * 17 + 7).toByte() }
        storeKeys(bob, KeyBundle(bobDek, bobPrivkey, bobPubkey))
        val aliceBody = okBody("alice", 1)
        val bobBody = okBody("bob", 2)
        assertEquals("sync-api: alice wrapped_dek", quoted(wrappedDekText), memberOf(aliceBody, "wrapped_dek"))
        assertEquals("sync-api: alice box_pubkey", quoted(boxPubkeyText), memberOf(aliceBody, "box_pubkey"))
        assertEquals("sync-api: bob wrapped_dek", quoted(KeyPatterns.text(bobDek)), memberOf(bobBody, "wrapped_dek"))
        assertEquals("sync-api: bob wrapped_box_privkey", quoted(KeyPatterns.text(bobPrivkey)), memberOf(bobBody, "wrapped_box_privkey"))
        assertEquals("sync-api: bob box_pubkey", quoted(KeyPatterns.text(bobPubkey)), memberOf(bobBody, "box_pubkey"))
    }

    @Test
    fun `a wrong verifier and an unknown name get 401 and the body holds no key text`() {
        storeBase(register("alice", 1))
        for ((label, reply) in listOf("wrong verifier" to loginAs("alice", 99), "unknown name" to loginAs("nobody", 1))) {
            assertEquals("sync-api: $label status", HttpStatusCode.Unauthorized, reply.status)
            assertEquals("sync-api: $label body", unauthorizedBody, reply.body)
            for (text in listOf(wrappedDekText, wrappedBoxPrivkeyText, boxPubkeyText)) {
                assertFalse("sync-api: the $label body holds a key text", reply.body.contains(text))
            }
        }
    }

    @Test
    fun `the nine member names appear once each in the documented order`() {
        val id = register("alice", 1)
        storeBase(id)
        presetPrev(temp, id, KeyPatterns.prevWrappedDek())
        val body = okBody("alice", 1)
        val names = listOf(
            "token", "role", "wrapped_dek", "wrapped_box_privkey", "box_pubkey",
            "key_version", "prev_wrapped_dek", "reset_at", "rekeyed_at",
        )
        var previous = -1
        for (name in names) {
            val marker = "\"$name\":"
            val first = body.indexOf(marker)
            assertTrue("sync-api: member $name is missing: $body", first >= 0)
            assertEquals("sync-api: member $name appears more than once", first, body.lastIndexOf(marker))
            assertTrue("sync-api: member $name is out of order: $body", first > previous)
            previous = first
        }
    }
}
