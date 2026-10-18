package dev.breaker.server.syncapi.accounts

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

internal class UsernameRulesTest {

    @Test
    fun `isValid accepts names made of the allowed characters`() {
        val names = listOf("a", "a".repeat(64), "A.b_c-9", "bob")
        for (name in names) {
            assertTrue("'$name' must be a valid username", UsernameRules.isValid(name))
        }
    }

    @Test
    fun `isValid refuses empty and over-long names`() {
        assertFalse("the empty name", UsernameRules.isValid(""))
        assertFalse("65 characters", UsernameRules.isValid("a".repeat(65)))
    }

    @Test
    fun `isValid refuses whitespace, control characters and other ASCII punctuation`() {
        val names = listOf("a b", " a", "a ", "a\tb", "a\n", "a\u0007", "a/b", "a@b")
        for (name in names) {
            assertFalse("'${name.replace("\n", "\\n")}' must be refused", UsernameRules.isValid(name))
        }
    }

    @Test
    fun `isValid refuses non-ASCII letters and digits`() {
        assertFalse("a Latin letter with an accent", UsernameRules.isValid("\u00e9"))
        assertFalse("an Arabic-Indic digit", UsernameRules.isValid("\u0668"))
    }

    @Test
    fun `isValid refuses a trailing newline even when the rest is allowed`() {
        val name = "a".repeat(64) + "\n"
        assertEquals("the test input is 65 characters", 65, name.length)
        assertFalse("64 allowed characters plus a newline", UsernameRules.isValid(name))
        assertFalse("a short name plus a newline", UsernameRules.isValid("bob\n"))
    }

    @Test
    fun `fold lower-cases ASCII letters and leaves the other characters`() {
        assertEquals("Bob", "bob", UsernameRules.fold("Bob"))
        assertEquals("ALICE.x-9", "alice.x-9", UsernameRules.fold("ALICE.x-9"))
    }
}
