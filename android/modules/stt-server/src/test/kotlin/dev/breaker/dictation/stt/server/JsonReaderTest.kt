package dev.breaker.dictation.stt.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Accepted inputs of the strict JSON reader, and the depth limit. */
class JsonReaderTest {

    @Test
    fun `parses an empty object and an empty array`() {
        assertEquals("empty object", JsonValue.Obj(emptyMap()), JsonReader.parse("{}"))
        assertEquals("empty array", JsonValue.Arr(emptyList()), JsonReader.parse(" [ ] "))
    }

    @Test
    fun `parses nested objects and arrays with their shape kept`() {
        val value = JsonReader.parse("""{"a":[1,{"b":"x"}],"c":true}""")
        val expected = JsonValue.Obj(
            mapOf(
                "a" to JsonValue.Arr(
                    listOf(JsonValue.Num("1"), JsonValue.Obj(mapOf("b" to JsonValue.Str("x")))),
                ),
                "c" to JsonValue.Bool(true),
            ),
        )
        assertEquals("nested shape", expected, value)
    }

    @Test
    fun `keeps a number as the exact text the input had`() {
        assertEquals("zero", JsonValue.Num("0"), JsonReader.parse("0"))
        assertEquals("fraction", JsonValue.Num("1.5"), JsonReader.parse("1.5"))
        assertEquals("exponent", JsonValue.Num("-0.5E+3"), JsonReader.parse("-0.5E+3"))
    }

    @Test
    fun `decodes an escaped quote and an escaped backslash`() {
        // JSON text is "a\"b\\c" and it decodes to a"b\c
        assertEquals(
            "quote and backslash",
            JsonValue.Str("a\"b\\c"),
            JsonReader.parse("\"a\\\"b\\\\c\""),
        )
    }

    @Test
    fun `decodes the slash and the control escapes`() {
        // JSON text is "\/\b\f\n\r\t" and it decodes to slash, BS, FF, LF, CR, TAB
        val json = "\"\\/\\b\\f\\n\\r\\t\""
        val expected = "/\b\u000C\n\r\t"
        assertEquals("short escapes", JsonValue.Str(expected), JsonReader.parse(json))
    }

    @Test
    fun `decodes a unicode escape and a surrogate pair`() {
        assertEquals("unicode escape", JsonValue.Str("A"), JsonReader.parse("\"\\u0041\""))
        assertEquals(
            "surrogate pair",
            JsonValue.Str("\uD83D\uDE00"),
            JsonReader.parse("\"\\uD83D\\uDE00\""),
        )
    }

    @Test
    fun `accepts space tab carriage return and line feed around the value`() {
        val value = JsonReader.parse(" \t\r\n{\"a\" : 1 }\r\n\t ")
        assertEquals("whitespace", JsonValue.Obj(mapOf("a" to JsonValue.Num("1"))), value)
    }

    @Test
    fun `accepts a top level scalar of each kind`() {
        assertEquals("true", JsonValue.Bool(true), JsonReader.parse("true"))
        assertEquals("false", JsonValue.Bool(false), JsonReader.parse("false"))
        assertEquals("null", JsonValue.Null, JsonReader.parse("null"))
        assertEquals("string", JsonValue.Str("x"), JsonReader.parse("\"x\""))
    }

    @Test
    fun `parses a done job reply with a result object and a segment`() {
        val body = """{"status":"done","result":{"text":"Hi","segments":[{"start":0.0,"end":1.5,"text":"Hi"}],"language":"en"}}"""
        val root = JsonReader.parse(body) as JsonValue.Obj
        assertEquals("status text", JsonValue.Str("done"), root.fields["status"])
        assertTrue("result is an object", root.fields["result"] is JsonValue.Obj)
    }

    @Test
    fun `accepts nesting exactly at the depth limit`() {
        val text = "[".repeat(JsonReader.MAX_DEPTH) + "]".repeat(JsonReader.MAX_DEPTH)
        assertTrue("16 levels parse", JsonReader.parse(text) is JsonValue.Arr)
    }

    @Test
    fun `refuses nesting one level deeper than the depth limit`() {
        val text = "[".repeat(JsonReader.MAX_DEPTH + 1) + "]".repeat(JsonReader.MAX_DEPTH + 1)
        refuse("17 levels", text)
    }

    @Test
    fun `accepts sixteen levels of nested arrays as a fixed number`() {
        val text = "[".repeat(16) + "]".repeat(16)
        assertTrue("16 array levels parse", JsonReader.parse(text) is JsonValue.Arr)
    }

    @Test
    fun `refuses seventeen levels of nested arrays as a fixed number`() {
        refuse("17 array levels", "[".repeat(17) + "]".repeat(17))
    }

    @Test
    fun `accepts sixteen levels of nested objects as a fixed number`() {
        // 15 wrappers of {"a": are levels 1 to 15, and the empty object {} is level 16.
        val text = "{\"a\":".repeat(15) + "{}" + "}".repeat(15)
        var node: JsonValue = JsonReader.parse(text)
        var levels = 0
        while (node is JsonValue.Obj) {
            levels++
            node = node.fields.values.firstOrNull() ?: break
        }
        assertEquals("object levels", 16, levels)
    }

    @Test
    fun `refuses seventeen levels of nested objects as a fixed number`() {
        // 16 wrappers of {"a": plus the empty object {} make seventeen levels.
        refuse("17 object levels", "{\"a\":".repeat(16) + "{}" + "}".repeat(16))
    }

    private fun refuse(label: String, text: String) {
        try {
            JsonReader.parse(text)
        } catch (e: JsonSyntaxException) {
            return
        }
        fail("accepted but must be refused: $label")
    }
}
