package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The text form of a cipher text, as the server stores it. Sealing an empty
 * message leaves no ciphertext bytes, so its ciphertext string is empty; the
 * nonce and the tag are still there and still required.
 */
class EncryptedTextTest {
    private fun refused(block: () -> Unit): String {
        try {
            block()
        } catch (expected: IllegalArgumentException) {
            return expected.message.orEmpty()
        }
        fail("expected the value to be refused")
        return ""
    }

    @Test
    fun `an empty ciphertext string is a valid encrypted text`() {
        val text = EncryptedText(ciphertext = "", nonce = "bm9uY2U=", tag = "dGFn")

        assertEquals("", text.ciphertext)
        assertEquals("bm9uY2U=", text.nonce)
        assertEquals("dGFn", text.tag)
        assertEquals(text, EncryptedText("", "bm9uY2U=", "dGFn"))
    }

    @Test
    fun `a whitespace-only ciphertext is still refused`() {
        for (blank in listOf(" ", "  ", "\t", "\n", " \t\n ")) {
            val message = refused { EncryptedText(ciphertext = blank, nonce = "bm9uY2U=", tag = "dGFn") }
            assertTrue("ciphertext ${blank.length} chars: $message", message.contains("ciphertext"))
        }
    }

    @Test
    fun `a missing nonce or tag is refused even when the ciphertext is empty`() {
        for (blank in listOf("", " ", "\t")) {
            val nonce = refused { EncryptedText(ciphertext = "", nonce = blank, tag = "dGFn") }
            assertTrue("nonce: $nonce", nonce.contains("nonce"))
            val tag = refused { EncryptedText(ciphertext = "", nonce = "bm9uY2U=", tag = blank) }
            assertTrue("tag: $tag", tag.contains("tag"))
        }
    }

    @Test
    fun `a text with ciphertext still needs its nonce and tag`() {
        val nonce = refused { EncryptedText(ciphertext = "Y2lwaGVy", nonce = "", tag = "dGFn") }
        assertTrue("nonce: $nonce", nonce.contains("nonce"))
        val tag = refused { EncryptedText(ciphertext = "Y2lwaGVy", nonce = "bm9uY2U=", tag = "") }
        assertTrue("tag: $tag", tag.contains("tag"))
        assertEquals("Y2lwaGVy", EncryptedText("Y2lwaGVy", "bm9uY2U=", "dGFn").ciphertext)
    }
}
