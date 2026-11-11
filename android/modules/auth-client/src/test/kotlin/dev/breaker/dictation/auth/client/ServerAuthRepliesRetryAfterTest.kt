package dev.breaker.dictation.auth.client

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Retry-After rule of a 429: seconds only, digits only, 1..3600, anything else is no number. */
class ServerAuthRepliesRetryAfterTest {
    private fun parsed(value: String): Int? =
        ServerAuthReplies.retryAfterSeconds(listOf("Retry-After" to value))

    @Test
    fun `the bounds 1 and 3600 are kept`() {
        assertEquals("android_auth_client: lower bound", 1, parsed("1"))
        assertEquals("android_auth_client: upper bound", 3600, parsed("3600"))
        assertEquals("android_auth_client: a plain value", 30, parsed("30"))
    }

    @Test
    fun `0 and 3601 and a huge value are no number and nothing throws`() {
        assertNull("android_auth_client: zero", parsed("0"))
        assertNull("android_auth_client: one over the bound", parsed("3601"))
        assertNull("android_auth_client: a value beyond Int", parsed("99999999999999999999"))
    }

    @Test
    fun `a sign, a decimal, an inner space and another script are no number`() {
        assertNull("android_auth_client: plus sign", parsed("+30"))
        assertNull("android_auth_client: minus sign", parsed("-5"))
        assertNull("android_auth_client: decimal", parsed("1.5"))
        assertNull("android_auth_client: inner space", parsed("3 0"))
        assertNull("android_auth_client: Arabic-Indic digits", parsed("\u0663\u0660"))
    }

    @Test
    fun `an http date and an empty value are no number`() {
        assertNull("android_auth_client: a date", parsed("Wed, 21 Oct 2026 07:28:00 GMT"))
        assertNull("android_auth_client: empty", parsed(""))
        assertNull("android_auth_client: only spaces", parsed("   "))
    }

    @Test
    fun `spaces and tabs around the digits are trimmed`() {
        assertEquals("android_auth_client: trimmed", 30, parsed(" 30 "))
        assertEquals("android_auth_client: leading tab", 30, parsed("\t30"))
        assertEquals("android_auth_client: trailing tab", 30, parsed("30\t"))
    }

    @Test
    fun `the header name is matched without regard to case and a missing header is no number`() {
        assertEquals(
            "android_auth_client: lower case name",
            30,
            ServerAuthReplies.retryAfterSeconds(listOf("retry-after" to "30")),
        )
        assertNull("android_auth_client: no header", ServerAuthReplies.retryAfterSeconds(emptyList()))
        assertNull(
            "android_auth_client: another header",
            ServerAuthReplies.retryAfterSeconds(listOf("Content-Type" to "30")),
        )
    }

    @Test
    fun `a repeated header is no number`() {
        assertNull(
            "android_auth_client: two values",
            ServerAuthReplies.retryAfterSeconds(listOf("Retry-After" to "30", "retry-after" to "60")),
        )
    }
}
