package dev.breaker.dictation.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * The box key pair: a private key and a 32 byte public key that travel together.
 *
 * The public half is copied in and copied out, [BoxKeyPair.wipe] zeroes the private half
 * only, and the text of the pair never shows a key byte.
 *
 * Every size in these tests is a literal number, so a wrong constant in the production
 * code cannot also make the tests agree with it.
 */
class BoxKeyPairTest {
    private fun filled(size: Int, value: Int): ByteArray = ByteArray(size) { value.toByte() }

    private fun boxKey(value: Int): BoxPrivateKey = BoxPrivateKey(filled(32, value))

    /** Bytes start, start + 1, ... so that with start 0xA0 index 5 is 0xA5. */
    private fun counting(start: Int): ByteArray = ByteArray(32) { (start + it).toByte() }

    private fun refusedMessage(what: String, block: () -> Unit): String {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return expected.message.orEmpty()
        }
        fail("$what was accepted but must be refused with IllegalArgumentException")
        return ""
    }

    @Test
    fun `a public key of 31 or 33 bytes is refused and 32 bytes is accepted`() {
        for (size in listOf(31, 33)) {
            val what = "a public key of $size bytes"
            val message = refusedMessage(what) { BoxKeyPair(boxKey(1), filled(size, 2)) }
            assertEquals("A box public key must be 32 bytes, got $size", message)
        }
        val pair = BoxKeyPair(boxKey(1), filled(32, 2))
        assertEquals("a 32 byte public key must be accepted", 32, pair.copyPublicKey().size)
    }

    @Test
    fun `the public key is copied in`() {
        val source = filled(32, 0x11)
        val pair = BoxKeyPair(boxKey(1), source)
        source.fill(0x22.toByte())
        assertArrayEquals("the pair changed with the source array", filled(32, 0x11), pair.copyPublicKey())
    }

    @Test
    fun `copyPublicKey returns a new array each call and editing it changes nothing`() {
        val pair = BoxKeyPair(boxKey(1), filled(32, 0x11))
        val first = pair.copyPublicKey()
        val second = pair.copyPublicKey()
        assertNotSame("copyPublicKey returned the same array twice", first, second)
        first[0] = 0x7F
        assertArrayEquals("the pair changed when a copy was edited", filled(32, 0x11), pair.copyPublicKey())
    }

    @Test
    fun `the private key is the instance passed in`() {
        val key = boxKey(1)
        assertSame("privateKey is not the instance passed in", key, BoxKeyPair(key, filled(32, 2)).privateKey)
    }

    @Test
    fun `wipe zeroes the private key and leaves the public key`() {
        val pair = BoxKeyPair(boxKey(0x33), filled(32, 0x44))
        assertArrayEquals("the private key was not intact", filled(32, 0x33), pair.privateKey.copyBytes())

        pair.wipe()

        assertArrayEquals(
            "the private key is not zero after wipe",
            ByteArray(32),
            pair.privateKey.copyBytes(),
        )
        assertArrayEquals("wipe changed the public key", filled(32, 0x44), pair.copyPublicKey())
    }

    @Test
    fun `the text of a pair shows no key byte`() {
        val pair = BoxKeyPair(BoxPrivateKey(counting(0xA0)), counting(0x60))
        val window = counting(0xA0).copyOfRange(0, 6)
        val publicWindow = counting(0x60).copyOfRange(0, 6)
        val shown = listOf(
            165.toString(),
            (-91).toString(),
            165.toString(16),
            165.toString(16).uppercase(),
            window.joinToString(", "),
            window.joinToString(" ") { (it.toInt() and 0xFF).toString() },
            window.joinToString("") { "%02x".format(it) },
            window.joinToString("") { "%02X".format(it) },
            publicWindow.joinToString(", "),
            publicWindow.joinToString(" ") { (it.toInt() and 0xFF).toString() },
            publicWindow.joinToString("") { "%02x".format(it) },
        )
        for (text in listOf(pair.toString(), run { pair.wipe(); pair.toString() })) {
            assertEquals("BoxKeyPair(public key 32 bytes, private key hidden)", text)
            assertFalse("the text shows an array identity", text.contains("[B@"))
            assertFalse("the text names a key field with an equals sign", text.contains("Key="))
            for (bytes in shown) {
                assertFalse("the text shows the key bytes $bytes", text.contains(bytes))
            }
        }
    }

    @Test
    fun `a box key pair is not a data class`() {
        val names = BoxKeyPair::class.java.declaredMethods.map { it.name }
        val message = "BoxKeyPair gained generated copy or component functions that could duplicate keys"
        assertFalse(message, names.contains("copy"))
        assertFalse(message, names.any { it.startsWith("component") })
    }
}
