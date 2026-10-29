package dev.breaker.server.syncapi.sync

import org.junit.Assert.assertEquals
import org.junit.Test

/** The JSON string writer, pinned by the exact output of each escape it writes. */
internal class JsonTextTest {

    private fun escaped(text: String): String {
        val out = StringBuilder()
        appendJsonString(out, text)
        return out.toString()
    }

    @Test
    fun `a backspace is written as the two characters backslash and b`() {
        assertEquals("\"\\b\"", escaped("\b"))
    }

    @Test
    fun `a form feed is written as the two characters backslash and f`() {
        assertEquals("\"\\f\"", escaped("\u000C"))
    }

    @Test
    fun `a carriage return is written as the two characters backslash and r`() {
        assertEquals("\"\\r\"", escaped("\r"))
    }

    @Test
    fun `a newline and a tab are written as backslash n and backslash t`() {
        assertEquals("\"a\\n\\tb\"", escaped("a\n\tb"))
    }

    @Test
    fun `a quotation mark and a backslash are escaped`() {
        assertEquals("\"a\\\"b\\\\c\"", escaped("a\"b\\c"))
    }

    @Test
    fun `another control character is written as a six character unicode escape`() {
        assertEquals("\"\\u0001\\u001f\"", escaped("\u0001\u001f"))
    }
}
