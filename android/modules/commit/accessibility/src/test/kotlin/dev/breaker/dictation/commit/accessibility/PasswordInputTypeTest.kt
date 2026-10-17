package dev.breaker.dictation.commit.accessibility

import android.text.InputType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which raw input types count as a password, and that flags above the variation do not change
 * the answer.
 *
 * The inputs are plain integers, and each comment names the android.text.InputType constants it
 * is built from. The last test pins those constants to the platform values, so a copy in the rule
 * file that drifts from them is caught by the tests that use it.
 */
internal class PasswordInputTypeTest {

    @Test
    fun `a text password is a password`() {
        // TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_PASSWORD
        assertTrue("commit/accessibility: a text password was not refused", isPasswordInputType(0x81))
    }

    @Test
    fun `a text visible password is a password`() {
        // TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        assertTrue("commit/accessibility: a text visible password was not refused", isPasswordInputType(0x91))
    }

    @Test
    fun `a text web password is a password`() {
        // TYPE_CLASS_TEXT or TYPE_TEXT_VARIATION_WEB_PASSWORD
        assertTrue("commit/accessibility: a text web password was not refused", isPasswordInputType(0xE1))
    }

    @Test
    fun `a number password is a password`() {
        // TYPE_CLASS_NUMBER or TYPE_NUMBER_VARIATION_PASSWORD
        assertTrue("commit/accessibility: a number password was not refused", isPasswordInputType(0x12))
    }

    @Test
    fun `a plain text field is not a password`() {
        // TYPE_CLASS_TEXT alone, variation 0
        assertFalse("commit/accessibility: a plain text field was refused", isPasswordInputType(0x1))
    }

    @Test
    fun `a plain number field is not a password`() {
        // TYPE_CLASS_NUMBER alone, variation 0
        assertFalse("commit/accessibility: a plain number field was refused", isPasswordInputType(0x2))
    }

    @Test
    fun `the number password variation in the text class is not a password`() {
        // TYPE_CLASS_TEXT or TYPE_NUMBER_VARIATION_PASSWORD, which is a URI field
        assertFalse("commit/accessibility: a text field with the number variation was refused", isPasswordInputType(0x11))
    }

    @Test
    fun `the text password variation in the number class is not a password`() {
        // TYPE_CLASS_NUMBER or TYPE_TEXT_VARIATION_PASSWORD
        assertFalse("commit/accessibility: a number field with the text variation was refused", isPasswordInputType(0x82))
    }

    @Test
    fun `a field of another class is not a password even with the number password bits`() {
        // TYPE_CLASS_DATETIME with the date variation (0x10), the same bits as the number password variation
        assertFalse("commit/accessibility: a date field was refused", isPasswordInputType(0x14))
        // TYPE_CLASS_PHONE, variation 0
        assertFalse("commit/accessibility: a phone field was refused", isPasswordInputType(0x3))
    }

    @Test
    fun `zero is not a password`() {
        assertFalse("commit/accessibility: an input type of zero was refused", isPasswordInputType(0x0))
    }

    @Test
    fun `flags above the variation do not change the answer`() {
        val passwords: List<Int> = listOf(0x81, 0x91, 0xE1, 0x12)
        // TYPE_TEXT_FLAG_MULTI_LINE, TYPE_TEXT_FLAG_NO_SUGGESTIONS and TYPE_TEXT_FLAG_CAP_WORDS
        val flags: List<Int> = listOf(0x20000, 0x80000, 0x2000)
        for (password in passwords) {
            for (flag in flags) {
                assertTrue(
                    "commit/accessibility: a flag above the variation changed a password answer",
                    isPasswordInputType(password or flag),
                )
            }
        }
    }

    @Test
    fun `the constants the rule copies equal the android values`() {
        assertEquals("commit/accessibility: TYPE_MASK_CLASS differs from the copy", 0xF, InputType.TYPE_MASK_CLASS)
        assertEquals("commit/accessibility: TYPE_MASK_VARIATION differs from the copy", 0xFF0, InputType.TYPE_MASK_VARIATION)
        assertEquals("commit/accessibility: TYPE_CLASS_TEXT differs from the copy", 0x1, InputType.TYPE_CLASS_TEXT)
        assertEquals("commit/accessibility: TYPE_CLASS_NUMBER differs from the copy", 0x2, InputType.TYPE_CLASS_NUMBER)
        assertEquals(
            "commit/accessibility: TYPE_TEXT_VARIATION_PASSWORD differs from the copy",
            0x80,
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
        )
        assertEquals(
            "commit/accessibility: TYPE_TEXT_VARIATION_VISIBLE_PASSWORD differs from the copy",
            0x90,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
        )
        assertEquals(
            "commit/accessibility: TYPE_TEXT_VARIATION_WEB_PASSWORD differs from the copy",
            0xE0,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
        )
        assertEquals(
            "commit/accessibility: TYPE_NUMBER_VARIATION_PASSWORD differs from the copy",
            0x10,
            InputType.TYPE_NUMBER_VARIATION_PASSWORD,
        )
    }
}
