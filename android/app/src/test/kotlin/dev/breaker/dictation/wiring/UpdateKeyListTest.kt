package dev.breaker.dictation.wiring

import java.security.SecureRandom
import java.util.Base64
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateKeyListTest {

    private val random = SecureRandom()

    private fun randomKey(size: Int): ByteArray {
        val bytes = ByteArray(size)
        random.nextBytes(bytes)
        return bytes
    }

    private fun encode(bytes: ByteArray): String = Base64.getEncoder().encodeToString(bytes)

    @Test
    fun `a valid single key parses to its own 32 bytes`() {
        val key = randomKey(32)
        val keys = UpdateKeyList.parse(encode(key) + "\n")
        assertEquals("app: one key line must give one key", 1, keys.size)
        assertArrayEquals("app: the parsed key must be the bytes that were encoded", key, keys[0])
    }

    @Test
    fun `several keys come back in the order they were written`() {
        val first = randomKey(32)
        val second = randomKey(32)
        val third = randomKey(32)
        val keys = UpdateKeyList.parse(listOf(first, second, third).joinToString("\n") { encode(it) })
        assertEquals("app: three key lines must give three keys", 3, keys.size)
        assertArrayEquals("app: the first key must come first", first, keys[0])
        assertArrayEquals("app: the second key must come second", second, keys[1])
        assertArrayEquals("app: the third key must come third", third, keys[2])
    }

    @Test
    fun `blank lines and comment lines are skipped`() {
        val key = randomKey(32)
        val text = "# the owner's list\n\n   \n  # indented comment\n" + encode(key) + "\n# retired key\n"
        val keys = UpdateKeyList.parse(text)
        assertEquals("app: blank and comment lines must not count", 1, keys.size)
        assertArrayEquals("app: the one key must be the key line", key, keys[0])
    }

    @Test
    fun `CRLF line ends are accepted`() {
        val first = randomKey(32)
        val second = randomKey(32)
        val keys = UpdateKeyList.parse(encode(first) + "\r\n" + encode(second) + "\r\n")
        assertEquals("app: two CRLF key lines must give two keys", 2, keys.size)
        assertArrayEquals("app: the first CRLF key must be the first key", first, keys[0])
        assertArrayEquals("app: the second CRLF key must be the second key", second, keys[1])
    }

    @Test
    fun `a key of 31 bytes empties the whole list`() {
        val good = randomKey(32)
        val text = encode(good) + "\n" + encode(randomKey(31))
        assertTrue("app: a 31 byte key must empty the whole list", UpdateKeyList.parse(text).isEmpty())
    }

    @Test
    fun `a key of 33 bytes empties the whole list`() {
        val good = randomKey(32)
        val text = encode(good) + "\n" + encode(randomKey(33))
        assertTrue("app: a 33 byte key must empty the whole list", UpdateKeyList.parse(text).isEmpty())
    }

    @Test
    fun `text that is not base64 gives no key`() {
        assertTrue("app: plain words must give no key", UpdateKeyList.parse("this is not a key at all").isEmpty())
        assertTrue("app: symbols must give no key", UpdateKeyList.parse("!!!!").isEmpty())
    }

    @Test
    fun `a key without its padding is not canonical and empties the list`() {
        val encoded = encode(randomKey(32))
        assertTrue("app: the sample must end with its padding character", encoded.endsWith("="))
        assertTrue("app: a key without padding must give no key", UpdateKeyList.parse(encoded.trimEnd('=')).isEmpty())
    }

    @Test
    fun `URL-safe characters are refused while the standard text is accepted`() {
        val bytes = ByteArray(32) { 0xFF.toByte() }
        val standard = encode(bytes)
        val urlSafe = Base64.getUrlEncoder().encodeToString(bytes)
        assertTrue("app: the sample must use the URL-safe characters", urlSafe.contains('_'))
        assertEquals("app: the standard text of the sample must give one key", 1, UpdateKeyList.parse(standard).size)
        assertTrue("app: URL-safe text must give no key", UpdateKeyList.parse(urlSafe).isEmpty())
    }

    @Test
    fun `one bad line among good lines empties everything`() {
        val good = randomKey(32)
        val text = encode(good) + "\nnot-a-key!\n" + encode(randomKey(32))
        assertTrue("app: one bad line must empty the whole answer", UpdateKeyList.parse(text).isEmpty())
    }

    @Test
    fun `empty input gives an empty list`() {
        assertTrue("app: empty input must give an empty list", UpdateKeyList.parse("").isEmpty())
    }

    @Test
    fun `input with only comment lines gives an empty list`() {
        assertTrue("app: comment lines alone must give an empty list", UpdateKeyList.parse("# one\n# two\n").isEmpty())
    }

    @Test
    fun `a repeated key is kept once in first order`() {
        val key = randomKey(32)
        val other = randomKey(32)
        val keys = UpdateKeyList.parse(encode(key) + "\n" + encode(other) + "\n" + encode(key) + "\n")
        assertEquals("app: a repeated key must be kept once", 2, keys.size)
        assertArrayEquals("app: the first key must stay first", key, keys[0])
        assertArrayEquals("app: the other key must come second", other, keys[1])
    }

    @Test
    fun `returned arrays are copies that do not share state`() {
        val key = randomKey(32)
        val text = encode(key)
        val first = UpdateKeyList.parse(text)
        first[0][0] = (first[0][0] + 1).toByte()
        val second = UpdateKeyList.parse(text)
        assertFalse("app: two parses must not return the same array", first[0] === second[0])
        assertArrayEquals("app: a second parse must not see a change made to the first", key, second[0])
    }
}
