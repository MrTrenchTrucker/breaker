package dev.breaker.dictation.core.model

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/**
 * The two wrapped key types: the box private key sealed under the data key, and the
 * previous data key sealed under the new one.
 *
 * Each refuses an empty array and judges nothing else; the crypto module judges the size.
 * Each prints only its size.
 */
class WrappedKeysTest {
    private fun filled(size: Int, value: Int): ByteArray = ByteArray(size) { value.toByte() }

    private fun assertRefusesEmpty(what: String, build: () -> Any) {
        try {
            build()
        } catch (expected: IllegalArgumentException) {
            assertEquals("A wrapped key cannot be empty", expected.message)
            return
        }
        fail("$what was accepted but must be refused with IllegalArgumentException")
    }

    private fun assertPrintsOnlySize(expected: String, actual: String) {
        assertEquals("the text is not the fixed size-only text", expected, actual)
        assertFalse("the text shows an array identity", actual.contains("[B@"))
        val shownValues = listOf("199", "-57", "c7", "C7")
        for (shown in shownValues) {
            assertFalse("the text shows the byte value $shown", actual.contains(shown))
        }
    }

    private fun box(size: Int): String = WrappedBoxPrivateKey(filled(size, 0xC7)).toString()

    private fun previous(size: Int): String = WrappedPreviousDek(filled(size, 0xC7)).toString()

    @Test
    fun `the empty array is refused by each wrapped key type`() {
        assertRefusesEmpty("an empty WrappedBoxPrivateKey") { WrappedBoxPrivateKey(ByteArray(0)) }
        assertRefusesEmpty("an empty WrappedPreviousDek") { WrappedPreviousDek(ByteArray(0)) }
    }

    @Test
    fun `each wrapped key type prints its size and no byte`() {
        assertPrintsOnlySize("WrappedBoxPrivateKey(60 bytes)", box(60))
        assertPrintsOnlySize("WrappedPreviousDek(60 bytes)", previous(60))
        assertPrintsOnlySize("WrappedBoxPrivateKey(1 bytes)", box(1))
        assertPrintsOnlySize("WrappedPreviousDek(1 bytes)", previous(1))
    }

    @Test
    fun `bytes holds the same content that was given`() {
        val given = filled(60, 0x21)
        assertArrayEquals(filled(60, 0x21), WrappedBoxPrivateKey(given).bytes)
        assertArrayEquals(filled(60, 0x21), WrappedPreviousDek(given).bytes)
    }
}
