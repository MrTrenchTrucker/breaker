package dev.breaker.dictation.ui.screen.auth

/*
 * RED-first: the rules checked here do not exist on the pre-change tree, so every assertion that
 * names them fails before the policy is written. This header records that, as required for a
 * red test, because nothing compiles on this host and CI is the only compiler.
 */

import org.junit.Assert.assertEquals
import org.junit.Test

class AuthPolicyTest {

    /** A password built from [text], one char per character of the literal. */
    private fun typed(text: String): CharArray = text.toCharArray()

    /** The rating's name, so too-short and weak are not confused across ratings. */
    private fun ratingName(rating: AuthPolicy.Rating): String = rating.name

    @Test
    fun `a nine-character password is refused as too short`() {
        assertEquals("TooShort", ratingName(AuthPolicy.rate(typed("shortpass"))))
    }

    @Test
    fun `an eleven-character password is refused as too short`() {
        assertEquals("TooShort", ratingName(AuthPolicy.rate(typed("elevenchars"))))
    }

    @Test
    fun `a twelve-character password with no weak rule is ok`() {
        assertEquals("Ok", ratingName(AuthPolicy.rate(typed("q1w2e3r4t5y6"))))
    }

    @Test
    fun `twelve characters matching a common entry case-insensitively are weak`() {
        assertEquals("Weak", ratingName(AuthPolicy.rate(typed("PASSWORD1234"))))
        assertEquals("Weak", ratingName(AuthPolicy.rate(typed("Password1234"))))
    }

    @Test
    fun `twelve copies of a single character are weak`() {
        val password = CharArray(12) { 'a' }
        assertEquals("Weak", ratingName(AuthPolicy.rate(password)))
    }

    @Test
    fun `twelve copies of two characters repeated is not one repeated char and is ok`() {
        val password = CharArray(12) { if (it % 2 == 0) 'a' else 'b' }
        assertEquals("Ok", ratingName(AuthPolicy.rate(password)))
    }

    @Test
    fun `twelve digits are weak as digits only and a mixed string is ok`() {
        assertEquals("Weak", ratingName(AuthPolicy.rate(typed("73".repeat(6)))))
        assertEquals("Ok", ratingName(AuthPolicy.rate(typed("73".repeat(5) + "3a"))))
    }

}
