package dev.breaker.dictation.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.fail
import org.junit.Test

/**
 * The sealed box of an agent-token result: any non-empty byte array, copied in and copied
 * out, with a size that can be read without a copy and a text that shows only that size.
 *
 * Every size in these tests is a literal number, so a wrong constant in the production
 * code cannot also make the tests agree with it.
 */
class SealedAgentResultTest {
    private fun filled(size: Int, value: Int): ByteArray = ByteArray(size) { value.toByte() }

    /** Bytes start, start + 1, ... so that with start 0xA0 index 5 is 0xA5. */
    private fun counting(start: Int, size: Int): ByteArray = ByteArray(size) { (start + it).toByte() }

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
    fun `an empty array is refused`() {
        val message = refusedMessage("an empty SealedAgentResult") { SealedAgentResult(ByteArray(0)) }
        assertEquals("A sealed agent result cannot be empty", message)
    }

    @Test
    fun `the bytes are copied in`() {
        val source = filled(48, 0x11)
        val sealed = SealedAgentResult(source)
        source.fill(0x22.toByte())
        assertArrayEquals("the box changed with the source array", filled(48, 0x11), sealed.copyBytes())
    }

    @Test
    fun `copyBytes returns a new array each call and editing it changes nothing`() {
        val sealed = SealedAgentResult(filled(48, 0x11))
        val first = sealed.copyBytes()
        val second = sealed.copyBytes()
        assertNotSame("copyBytes returned the same array twice", first, second)
        first[0] = 0x7F
        assertArrayEquals("the box changed when a copy was edited", filled(48, 0x11), sealed.copyBytes())
    }

    @Test
    fun `the size equals the array length and core sets no limit`() {
        assertEquals("a 1 byte box", 1, SealedAgentResult(filled(1, 0x5A)).size)
        assertEquals("a 48 byte box", 48, SealedAgentResult(filled(48, 0x5A)).size)
        assertEquals("a 524337 byte box", 524337, SealedAgentResult(filled(524337, 0x5A)).size)
    }

    @Test
    fun `the text shows the size and no byte`() {
        val sealed = SealedAgentResult(counting(0xA0, 48))
        val window = counting(0xA0, 48).copyOfRange(0, 6)
        val shown = listOf(
            165.toString(),
            (-91).toString(),
            165.toString(16),
            165.toString(16).uppercase(),
            window.joinToString(", "),
            window.joinToString(" ") { (it.toInt() and 0xFF).toString() },
            window.joinToString("") { "%02x".format(it) },
            window.joinToString("") { "%02X".format(it) },
        )
        val text = sealed.toString()
        assertEquals("SealedAgentResult(48 bytes)", text)
        assertFalse("the text shows an array identity", text.contains("[B@"))
        for (bytes in shown) {
            assertFalse("the text shows the box bytes $bytes", text.contains(bytes))
        }
    }

    @Test
    fun `a sealed agent result is not a data class`() {
        val names = SealedAgentResult::class.java.declaredMethods.map { it.name }
        val message = "SealedAgentResult gained generated copy or component functions"
        assertFalse(message, names.contains("copy"))
        assertFalse(message, names.any { it.startsWith("component") })
    }
}
