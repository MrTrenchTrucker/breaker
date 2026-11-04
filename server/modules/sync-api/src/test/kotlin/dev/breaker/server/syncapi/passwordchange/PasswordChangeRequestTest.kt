package dev.breaker.server.syncapi.passwordchange

import dev.breaker.server.syncapi.accounts.KdfParams
import dev.breaker.server.syncapi.accounts.StoredVerifier
import dev.breaker.server.syncapi.auth.KeyPatterns
import dev.breaker.server.syncapi.db.TestDatabases
import java.nio.charset.StandardCharsets
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The request refuses a wrong size in each of its five byte members, names no value, copies in and out and prints a fixed text. */
internal class PasswordChangeRequestTest {

    private val stored = StoredVerifier("pbkdf2-hmac-sha256", 1000, ByteArray(16) { 5 }, ByteArray(32) { 6 })

    private fun bytes(size: Int): ByteArray = ByteArray(size) { i -> (i + 100).toByte() }

    /** A request with every member the right size except the one named by [member]: 0 salt, 1 dek, 2 privkey, 3 pubkey, 4 prev. */
    private fun requestWith(member: Int, size: Int): PasswordChangeRequest {
        val sizes = intArrayOf(16, 60, 60, 32, 60)
        sizes[member] = size
        return PasswordChangeRequest(
            bytes(sizes[0]),
            KdfParams(131072, 4, 2),
            1,
            stored,
            bytes(sizes[1]),
            bytes(sizes[2]),
            bytes(sizes[3]),
            bytes(sizes[4]),
            2,
        )
    }

    private fun assertRefused(member: Int, field: String, expectedSize: String, wrongSizes: List<Int>) {
        for (size in wrongSizes) {
            val failure = TestDatabases.expectFailure<IllegalArgumentException>("$field with $size bytes") {
                requestWith(member, size)
            }
            val message = failure.message.orEmpty()
            val bad = bytes(size)
            assertTrue("sync-api: the message must name the field $field: $message", message.contains(field))
            assertTrue("sync-api: the message must name the size $expectedSize: $message", message.contains(expectedSize))
            assertFalse("sync-api: the message holds the base64 of the value", message.contains(KeyPatterns.text(bad)))
            assertFalse("sync-api: the message holds the value as text", message.contains(String(bad, StandardCharsets.ISO_8859_1)))
            assertFalse("sync-api: the message holds the value as numbers", message.contains("100, 101"))
        }
    }

    @Test
    fun `the right sizes are accepted`() {
        val request = requestWith(0, 16)
        assertEquals("sync-api: key version", 2, request.newKeyVersion)
        assertEquals("sync-api: salt size", 16, request.newSalt.size)
        assertEquals("sync-api: dek size", 60, request.newWrappedDek.size)
        assertEquals("sync-api: privkey size", 60, request.newWrappedBoxPrivkey.size)
        assertEquals("sync-api: pubkey size", 32, request.newBoxPubkey.size)
        assertEquals("sync-api: prev size", 60, request.prevWrappedDek.size)
    }

    @Test
    fun `new_salt of 15 or 17 bytes is refused`() {
        assertRefused(0, "new_salt", "16", listOf(15, 17))
    }

    @Test
    fun `new_wrapped_dek of 59 or 61 bytes is refused`() {
        assertRefused(1, "new_wrapped_dek", "60", listOf(59, 61))
    }

    @Test
    fun `new_wrapped_box_privkey of 59 or 61 bytes is refused`() {
        assertRefused(2, "new_wrapped_box_privkey", "60", listOf(59, 61))
    }

    @Test
    fun `new_box_pubkey of 31 or 33 bytes is refused`() {
        assertRefused(3, "new_box_pubkey", "32", listOf(31, 33))
    }

    @Test
    fun `prev_wrapped_dek of 59 or 61 bytes is refused`() {
        assertRefused(4, "prev_wrapped_dek", "60", listOf(59, 61))
    }

    @Test
    fun `the request copies its inputs and answers copies`() {
        val salt = bytes(16)
        val dek = bytes(60)
        val request = PasswordChangeRequest(salt, KdfParams(131072, 4, 2), 1, stored, dek, bytes(60), bytes(32), bytes(60), 2)
        salt[0] = 0
        dek[0] = 0
        request.newSalt[0] = 0
        request.newWrappedDek[0] = 0
        request.newWrappedBoxPrivkey[0] = 0
        request.newBoxPubkey[0] = 0
        request.prevWrappedDek[0] = 0
        assertArrayEquals("sync-api: salt copy", bytes(16), request.newSalt)
        assertArrayEquals("sync-api: dek copy", bytes(60), request.newWrappedDek)
        assertArrayEquals("sync-api: privkey copy", bytes(60), request.newWrappedBoxPrivkey)
        assertArrayEquals("sync-api: pubkey copy", bytes(32), request.newBoxPubkey)
        assertArrayEquals("sync-api: prev copy", bytes(60), request.prevWrappedDek)
    }

    @Test
    fun `the request prints a fixed text with no key value`() {
        assertEquals("sync-api: request text", "PasswordChangeRequest", requestWith(0, 16).toString())
    }
}
