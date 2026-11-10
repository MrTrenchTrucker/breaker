package dev.breaker.server.syncapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Escaper table test for [escapeJsonString]. Each row feeds one input and asserts the exact
 * escaped output, then round-trips the output through a small decoder and compares with the input.
 * Backticked names use only letters digits spaces — no : . ; [ ] / < > and no backslash.
 */
internal class JsonTextEscapeTableTest {

    private fun decode(json: String): String {
        require(json.startsWith("\"") && json.endsWith("\""))
        val inner = json.substring(1, json.length - 1)
        val sb = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val ch = inner[i]
            if (ch == '\\' && i + 1 < inner.length) {
                i++
                when (inner[i]) {
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    'b' -> sb.append('\b')
                    'f' -> sb.append(0x0C.toChar())
                    'n' -> sb.append('\n')
                    'r' -> sb.append('\r')
                    't' -> sb.append('\t')
                    'u' -> {
                        val hex = inner.substring(i + 1, i + 5)
                        sb.append(Integer.parseInt(hex, 16).toChar())
                        i += 4
                    }
                    else -> sb.append(inner[i])
                }
            } else {
                sb.append(ch)
            }
            i++
        }
        return sb.toString()
    }

    @Test
    fun `a double quote inside a value is escaped`() {
        val escaped = escapeJsonString("a\"b\"c")
        assertEquals("\"a\\\"b\\\"c\"", escaped)
        assertTrue("round trip", decode(escaped) == "a\"b\"c")
    }

    @Test
    fun `a backslash inside a value is escaped`() {
        val escaped = escapeJsonString("a\\b\\c")
        assertEquals("\"a\\\\b\\\\c\"", escaped)
        assertTrue("round trip", decode(escaped) == "a\\b\\c")
    }

    @Test
    fun `a control character U+0000 is escaped as u0000`() {
        val escaped = escapeJsonString("\u0000")
        assertEquals("\"\\u0000\"", escaped)
        assertTrue("round trip", decode(escaped) == "\u0000")
    }

    @Test
    fun `a control character U+0001 is escaped as u0001`() {
        val escaped = escapeJsonString("\u0001")
        assertEquals("\"\\u0001\"", escaped)
        assertTrue("round trip", decode(escaped) == "\u0001")
    }

    @Test
    fun `a control character U+001F is escaped as u001f`() {
        val escaped = escapeJsonString("\u001f")
        assertEquals("\"\\u001f\"", escaped)
        assertTrue("round trip", decode(escaped) == "\u001f")
    }

    @Test
    fun `newline is escaped as backslash n`() {
        val escaped = escapeJsonString("line1\nline2")
        assertEquals("\"line1\\nline2\"", escaped)
        assertTrue("round trip", decode(escaped) == "line1\nline2")
    }

    @Test
    fun `backspace is escaped as backslash b`() {
        val escaped = escapeJsonString("a\bb")
        assertEquals("\"a\\bb\"", escaped)
        assertTrue("round trip", decode(escaped) == "a\bb")
    }

    @Test
    fun `formfeed is escaped as backslash f`() {
        val escaped = escapeJsonString("a\u000Cb")
        assertEquals("\"a\\fb\"", escaped)
        assertTrue("round trip", decode(escaped) == "a\u000Cb")
    }

    @Test
    fun `carriage return is escaped as backslash r`() {
        val escaped = escapeJsonString("a\rb")
        assertEquals("\"a\\rb\"", escaped)
        assertTrue("round trip", decode(escaped) == "a\rb")
    }

    @Test
    fun `tab is escaped as backslash t`() {
        val escaped = escapeJsonString("a\tb")
        assertEquals("\"a\\tb\"", escaped)
        assertTrue("round trip", decode(escaped) == "a\tb")
    }

    @Test
    fun `all four escape classes in one string`() {
        val escaped = escapeJsonString("a\"b\\c\u0007d")
        assertEquals("\"a\\\"b\\\\c\\u0007d\"", escaped)
        assertTrue("round trip", decode(escaped) == "a\"b\\c\u0007d")
    }

    @Test
    fun `U+2028 is passed through unchanged`() {
        val escaped = escapeJsonString("\u2028")
        assertEquals("\"\u2028\"", escaped)
        assertTrue("round trip", decode(escaped) == "\u2028")
    }

    @Test
    fun `a surrogate pair is passed through unchanged`() {
        val escaped = escapeJsonString("\ud83d\ude00")
        assertEquals("\"\ud83d\ude00\"", escaped)
        assertTrue("round trip", decode(escaped) == "\ud83d\ude00")
    }

    @Test
    fun `empty string escapes to two quotes`() {
        val escaped = escapeJsonString("")
        assertEquals("\"\"", escaped)
        assertTrue("round trip", decode(escaped) == "")
    }

    @Test
    fun `every escaper output round trips to the input`() {
        for (input in listOf(
            "plain",
            "a\"b",
            "a\\b",
            "\u0000",
            "\u0007",
            "\u001f",
            "\n",
            "\r",
            "\t",
            "\b",
            "\u000C",
            "a\"b\\c\u0007d",
            "\u2028",
            "\ud83d\ude00",
            "",
        )) {
            val escaped = escapeJsonString(input)
            assertTrue("starts with quote: $escaped", escaped.startsWith("\""))
            assertTrue("ends with quote: $escaped", escaped.endsWith("\""))
            assertTrue("round trip of $input", decode(escaped) == input)
        }
    }
}
