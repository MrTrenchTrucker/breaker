package dev.breaker.server.syncapi.tokens

import java.security.SecureRandom
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal class TokenSecretTest {

    private val alphabet: String = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789_-"

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { value -> "%02x".format(value.toInt() and 0xff) }

    private fun fakeRandom(fill: (Int) -> Byte): SecureRandom = object : SecureRandom() {
        override fun nextBytes(bytes: ByteArray) {
            for (index in bytes.indices) {
                bytes[index] = fill(index)
            }
        }
    }

    private fun assertKnownAnswer(case: String, random: SecureRandom, expectedText: String, expectedHashHex: String) {
        val secret = TokenSecret.generate(random)
        assertEquals("sync-api: text generated from $case", expectedText, secret.reveal())
        assertEquals("sync-api: hash of the secret generated from $case", expectedHashHex, hex(secret.hash()))
    }

    @Test
    fun `the sizes are 32 random bytes and 43 characters`() {
        assertEquals("sync-api: random byte count", 32, TokenSecret.RANDOM_BYTES)
        assertEquals("sync-api: text length", 43, TokenSecret.TEXT_LENGTH)
    }

    @Test
    fun `bytes 0 to 31 give the known text and hash`() {
        assertKnownAnswer(
            "bytes 0 to 31",
            fakeRandom { index -> index.toByte() },
            "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8",
            "ea866a757e4c38babfa8127cbe9a409d3e1f93a00ff1488ff735fcf917afffd0",
        )
    }

    @Test
    fun `32 bytes of 0xFF give the known text and hash`() {
        assertKnownAnswer(
            "32 bytes of 0xFF",
            fakeRandom { _ -> 0xFF.toByte() },
            "_".repeat(42) + "8",
            "225f7e75329dd45aa354975d73987319309393af3a4c6733bc13601a4f1b8796",
        )
    }

    @Test
    fun `32 bytes of 0x00 give the known text and hash`() {
        assertKnownAnswer(
            "32 bytes of 0x00",
            fakeRandom { _ -> 0.toByte() },
            "A".repeat(43),
            "0f007385b6f9d4b7eeb2748605afe1a984a0a3bfa3f014d09e2a784ce9e5cd1a",
        )
    }

    @Test
    fun `a non canonical spelling parses but hashes as its own text`() {
        val spelling = "_".repeat(42) + "9"
        val canonical = "_".repeat(42) + "8"
        val parsed = TokenSecret.parse(spelling)
        assertNotNull("sync-api: a 43 character url-safe text must parse", parsed)
        assertEquals(
            "sync-api: the hash must be taken over the presented text",
            "bbd97b7b40f97a62e23d1a9ae12b094f6d75eb8bf94d748fd350cd86c57a9fdd",
            hex(parsed!!.hash()),
        )
        assertNotEquals(
            "sync-api: a different spelling of the same bytes must not share a hash",
            hex(TokenSecret.parse(canonical)!!.hash()),
            hex(parsed.hash()),
        )
    }

    @Test
    fun `a generated secret is 43 url safe characters`() {
        val random = SecureRandom()
        for (attempt in 1..16) {
            val text = TokenSecret.generate(random).reveal()
            assertEquals("sync-api: generated text length", 43, text.length)
            for (character in text) {
                assertTrue("sync-api: '$character' is outside the url-safe alphabet", alphabet.indexOf(character) >= 0)
            }
        }
    }

    @Test
    fun `sixteen generated secrets are all different`() {
        val random = SecureRandom()
        val texts = HashSet<String>()
        for (attempt in 1..16) {
            texts.add(TokenSecret.generate(random).reveal())
        }
        assertEquals("sync-api: generated secrets collided", 16, texts.size)
    }

    @Test
    fun `reveal returns the same text every time`() {
        val secret = TokenSecret.generate(SecureRandom())
        assertEquals("sync-api: reveal changed between calls", secret.reveal(), secret.reveal())
    }

    @Test
    fun `parse accepts a generated text and gives it back`() {
        val generated = TokenSecret.generate(SecureRandom())
        val parsed = TokenSecret.parse(generated.reveal())
        assertNotNull("sync-api: a generated text must parse", parsed)
        assertEquals("sync-api: parse changed the text", generated.reveal(), parsed!!.reveal())
        assertArrayEquals("sync-api: parse changed the hash", generated.hash(), parsed.hash())
    }

    @Test
    fun `parse accepts every character of the alphabet`() {
        for (text in listOf(alphabet.take(43), alphabet.takeLast(43))) {
            assertNotNull("sync-api: text '$text' uses only url-safe characters and must parse", TokenSecret.parse(text))
        }
    }

    @Test
    fun `parse refuses every text that is not exactly 43 url safe characters`() {
        val body = "A".repeat(42)
        val cases: List<Pair<String, String>> = listOf(
            "an empty text" to "",
            "42 characters" to "A".repeat(42),
            "44 characters" to "A".repeat(44),
            "padding at the end" to body + "=",
            "a plus sign at the end" to body + "+",
            "a plus sign at the start" to "+" + body,
            "a slash in the middle" to "A".repeat(20) + "/" + "A".repeat(22),
            "a space inside" to "A".repeat(20) + " " + "A".repeat(22),
            "a trailing newline" to "A".repeat(43) + "\n",
            "a leading space" to " " + "A".repeat(43),
            "a non ascii letter inside" to "A".repeat(21) + "\u00e9" + "A".repeat(21),
            "a non ascii digit inside" to "A".repeat(21) + "\uFF11" + "A".repeat(21),
            "a null character inside" to "A".repeat(21) + "\u0000" + "A".repeat(21),
        )
        for ((name, text) in cases) {
            assertNull("sync-api: parse accepted $name", TokenSecret.parse(text))
        }
    }

    @Test
    fun `the hash is 32 bytes and a fresh array each call`() {
        val secret = TokenSecret.generate(SecureRandom())
        val first = secret.hash()
        assertEquals("sync-api: hash length", 32, first.size)
        val original = first.copyOf()
        for (index in first.indices) {
            first[index] = 0
        }
        assertArrayEquals("sync-api: changing a returned hash changed the next one", original, secret.hash())
    }

    @Test
    fun `two different secrets hash differently`() {
        val random = SecureRandom()
        val one = TokenSecret.generate(random)
        val two = TokenSecret.generate(random)
        assertFalse("sync-api: two secrets collided on their hash", one.hash().contentEquals(two.hash()))
    }

    @Test
    fun `toString and a string template never show the secret`() {
        val secret = TokenSecret.generate(fakeRandom { index -> index.toByte() })
        val templated = "value: $secret"
        assertEquals("sync-api: toString", "TokenSecret(redacted)", secret.toString())
        assertEquals("sync-api: string template", "value: TokenSecret(redacted)", templated)
        assertFalse("sync-api: toString leaked the secret", secret.toString().contains(secret.reveal()))
        assertFalse("sync-api: the template leaked the secret", templated.contains(secret.reveal()))
    }
}
