package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The return link parser: only the exact breaker handoff shape with a 43 character code is
 * accepted. Every other input, including null, is ignored, and each ignored case is checked
 * with the rest of the link valid so the one broken part is the only reason.
 */
class HandoffReturnTest {
    private val prefix = "breaker://handoff?code="
    private val goodCode = "DwBzhbb51LfusnSGBa_hqYSgo7-j8BTQnip4TOnlzRo"

    /** The code of a link that must be accepted. */
    private fun codeOf(link: String?): String {
        val result = parseHandoffReturn(link)
        assertTrue("the link must be accepted", result is HandoffReturn.Code)
        return (result as HandoffReturn.Code).code
    }

    private fun assertIgnored(link: String?) {
        assertSame("a link that must be ignored was not", HandoffReturn.Ignored, parseHandoffReturn(link))
    }

    @Test
    fun `the exact shape with a 43 character code gives that code`() {
        assertEquals(goodCode, codeOf(prefix + goodCode))
    }

    @Test
    fun `every kind of character in the code alphabet is accepted`() {
        val code = "ABCDEFGHIJKLMNOPQRSTUVWXYZ-_0123ghijklmnopq"
        assertEquals("the test code must be 43 characters", 43, code.length)
        assertEquals(code, codeOf(prefix + code))
    }

    @Test
    fun `a code of 42 or 44 characters is ignored`() {
        assertIgnored(prefix + "A".repeat(42))
        assertIgnored(prefix + "A".repeat(44))
    }

    @Test
    fun `null the empty string and an empty code are ignored`() {
        assertIgnored(null)
        assertIgnored("")
        assertIgnored(prefix)
        assertIgnored("breaker://handoff")
    }

    @Test
    fun `a scheme or host in another case is ignored`() {
        assertIgnored("Breaker://handoff?code=" + goodCode)
        assertIgnored("breaker://HANDOFF?code=" + goodCode)
        assertIgnored("BREAKER://handoff?code=" + goodCode)
    }

    @Test
    fun `another scheme or host is ignored`() {
        assertIgnored("http://handoff?code=" + goodCode)
        assertIgnored("https://handoff?code=" + goodCode)
        assertIgnored("breaker://other?code=" + goodCode)
        assertIgnored("breaker://handoffx?code=" + goodCode)
    }

    @Test
    fun `user info a port and a path are ignored`() {
        assertIgnored("breaker://u@handoff?code=" + goodCode)
        assertIgnored("breaker://handoff:443?code=" + goodCode)
        assertIgnored("breaker://handoff/x?code=" + goodCode)
        assertIgnored("breaker://handoff/?code=" + goodCode)
    }

    @Test
    fun `a fragment an extra member and a duplicate code are ignored`() {
        assertIgnored(prefix + goodCode + "#x")
        assertIgnored(prefix + goodCode + "&x=1")
        assertIgnored(prefix + goodCode + "&code=" + goodCode)
        assertIgnored("breaker://handoff?x=1&code=" + goodCode)
    }

    @Test
    fun `a bad character a trailing slash a trailing space and a newline are ignored`() {
        assertIgnored(prefix + "+" + goodCode.drop(1))
        assertIgnored(prefix + "=" + goodCode.drop(1))
        assertIgnored(prefix + "." + goodCode.drop(1))
        assertIgnored(prefix + goodCode + "/")
        assertIgnored(prefix + goodCode + " ")
        assertIgnored(prefix + goodCode + "\n")
        assertIgnored("breaker://handoff ?code=" + goodCode)
    }

    @Test
    fun `the code prints a fixed text with no code and two code results are different objects`() {
        val code = parseHandoffReturn(prefix + goodCode) as HandoffReturn.Code

        assertEquals("HandoffReturn.Code(redacted)", code.toString())
        assertFalse("toString shows the code", code.toString().contains(goodCode))
        assertNotEquals(
            "two codes compare equal",
            parseHandoffReturn(prefix + goodCode),
            parseHandoffReturn(prefix + goodCode),
        )
    }
}
