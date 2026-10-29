package dev.breaker.dictation.updater

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Pins what the strict JSON reader accepts and refuses. */
internal class JsonParserTest {
    @Test
    fun `a valid document with nested values is read`() {
        val value = JsonParser.parse("{\"a\":[1,\"x\",true,null,{\"b\":-0.5e+2}]}") as JsonValue.Obj
        val items = (value.fields.getValue("a") as JsonValue.Arr).items
        assertEquals(5, items.size)
        assertEquals(JsonValue.Bool(true), items[2])
        assertEquals(JsonValue.Null, items[3])
    }

    @Test
    fun `comments, trailing commas, single quotes and bare words are refused`() {
        refuses("{\"a\":1 /* c */}")
        refuses("// c\n{}")
        refuses("[1,]")
        refuses("{'a':1}")
        refuses("abc")
    }

    @Test
    fun `a leading plus, a leading zero, NaN, Infinity and a malformed number are refused`() {
        refuses("+1")
        refuses("01")
        refuses("-01")
        refuses("NaN")
        refuses("Infinity")
        refuses("-Infinity")
        refuses("1.")
        refuses("1e")
        refuses("1e+")
        refuses("-")
        refuses(".5")
        refuses("1.e3")
    }

    @Test
    fun `an empty document, a whitespace-only document, trailing text and a byte order mark are refused`() {
        refuses("")
        refuses("  \n\t ")
        refuses("{} x")
        refuses("[1] [2]")
        refuses("1 2")
        refuses("\uFEFF{}")
    }

    @Test
    fun `a raw control character inside a string is refused`() {
        refuses("\"a\nb\"")
        refuses("\"a\tb\"")
        refuses("\"a\u0001b\"")
    }

    @Test
    fun `an unknown escape letter and a short unicode escape are refused`() {
        refuses("\"\\x\"")
        refuses("\"\\u12\"")
        refuses("\"\\u12\u0661\"")
    }

    @Test
    fun `a lone surrogate is refused whether escaped or raw and a raw pair is accepted`() {
        refuses("\"\\uDC00\"")
        refuses("\"\\uD800\\u0041\"")
        refuses("\"\uD800x\"")
        refuses("\"\uDC00\"")
        val pair = JsonParser.parse("\"\uD83D\uDE00\"") as JsonValue.Str
        assertEquals("\uD83D\uDE00", pair.value)
    }

    @Test
    fun `an escaped surrogate pair is read as one character`() {
        val value = JsonParser.parse("\"\\uD83D\\uDE00\"") as JsonValue.Str
        assertEquals(2, value.value.length)
        assertEquals("\uD83D\uDE00", value.value)
    }

    @Test
    fun `a duplicate key is refused at any depth including a key written with an escape`() {
        refuses("{\"a\":1,\"a\":2}")
        refuses("{\"x\":{\"a\":1,\"a\":2}}")
        refuses("{\"a\":1,\"\\u0061\":2}")
    }

    @Test
    fun `nesting up to depth four is read and depth five is refused`() {
        assertTrue(JsonParser.parse("{\"x\":[[[1]]]}") is JsonValue.Obj)
        refuses("{\"x\":[[[[1]]]]}")
    }

    @Test
    fun `unclosed and mismatched containers are refused`() {
        refuses("[1")
        refuses("{")
        refuses("[1}")
    }

    private fun refuses(text: String) {
        try {
            JsonParser.parse(text)
        } catch (expected: JsonSyntaxException) {
            return
        }
        fail("accepted but must be refused: $text")
    }
}
