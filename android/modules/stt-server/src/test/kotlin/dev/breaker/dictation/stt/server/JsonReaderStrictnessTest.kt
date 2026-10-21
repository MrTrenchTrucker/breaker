package dev.breaker.dictation.stt.server

import org.junit.Assert.assertFalse
import org.junit.Assert.fail
import org.junit.Test

/** Every input the strict JSON reader must refuse, one rule per test. */
class JsonReaderStrictnessTest {

    @Test
    fun `refuses comments in any position`() {
        refuse("block comment inside object", "{\"a\":1 /* c */}")
        refuse("line comment before value", "// c\n{}")
    }

    @Test
    fun `refuses a trailing comma in an object and in an array`() {
        refuse("object trailing comma", "{\"a\":1,}")
        refuse("array trailing comma", "[1,]")
    }

    @Test
    fun `refuses single quotes, a leading plus sign, NaN and Infinity`() {
        refuse("single quotes", "{'a':1}")
        refuse("leading plus", "+1")
        refuse("NaN", "NaN")
        refuse("Infinity", "Infinity")
        refuse("negative Infinity", "-Infinity")
    }

    @Test
    fun `refuses a leading zero before another digit`() {
        refuse("01", "01")
        refuse("-01", "-01")
        refuse("00", "00")
    }

    @Test
    fun `refuses a malformed number`() {
        refuse("fraction with no digits", "1.")
        refuse("exponent with no digits", "1e")
        refuse("signed exponent with no digits", "1e+")
        refuse("minus alone", "-")
        refuse("leading point", ".5")
        refuse("point before exponent", "1.e3")
    }

    @Test
    fun `refuses a byte order mark at the start`() {
        refuse("BOM before object", "\uFEFF{}")
    }

    @Test
    fun `refuses a duplicate object key`() {
        refuse("duplicate key", "{\"a\":1,\"a\":2}")
    }

    @Test
    fun `refuses a lone low surrogate escape`() {
        refuse("low surrogate alone", "\"\\uDC00\"")
    }

    @Test
    fun `refuses a high surrogate escape with no low surrogate after it`() {
        refuse("high surrogate alone", "\"\\uD800\"")
        refuse("high surrogate then a non-low escape", "\"\\uD800\\u0041\"")
    }

    @Test
    fun `refuses raw line feed and raw control characters inside a string`() {
        refuse("raw line feed", "\"a\nb\"")
        refuse("raw U+0001", "\"a\u0001b\"")
    }

    @Test
    fun `refuses an unknown escape letter and a short unicode escape`() {
        refuse("unknown escape", "\"\\x\"")
        refuse("unicode escape with two hex digits", "\"\\u12\"")
        refuse("non-ASCII digit in unicode escape", "\"\\u12\u0661\"")
    }

    @Test
    fun `refuses an unterminated string`() {
        refuse("unterminated string", "\"abc")
    }

    @Test
    fun `refuses trailing characters after the value`() {
        refuse("object then letter", "{} x")
        refuse("two arrays", "[1] [2]")
        refuse("two numbers", "1 2")
        refuse("two literals", "true false")
    }

    @Test
    fun `refuses empty input and whitespace-only input`() {
        refuse("empty", "")
        refuse("whitespace only", "  \n\t ")
    }

    @Test
    fun `refuses a bare word and a missing colon`() {
        refuse("bare word", "abc")
        refuse("missing colon", "{\"a\" 1}")
    }

    @Test
    fun `refuses an unclosed or mismatched container`() {
        refuse("unclosed array", "[1")
        refuse("unclosed object", "{")
        refuse("array closed with brace", "[1}")
    }

    @Test
    fun `refuses a deep nesting without overflowing the stack`() {
        refuse("100000 open brackets", "[".repeat(100_000))
    }

    @Test
    fun `syntax error messages never carry the input text`() {
        val bodies = listOf(
            "{\"$CANARY\":",
            "\"$CANARY\\x\"",
            CANARY,
            "[1,$CANARY]",
            "{\"a\":1}$CANARY",
        )
        for (body in bodies) {
            val message = syntaxMessage(body)
            assertFalse("message must not carry the input text", message.contains(CANARY))
        }
    }

    private fun refuse(label: String, text: String) {
        try {
            JsonReader.parse(text)
        } catch (e: JsonSyntaxException) {
            return
        }
        fail("accepted but must be refused: $label")
    }

    private fun syntaxMessage(text: String): String {
        try {
            JsonReader.parse(text)
        } catch (e: JsonSyntaxException) {
            return e.message.orEmpty()
        }
        fail("accepted but must be refused")
        return ""
    }

    private companion object {
        const val CANARY = "CANARY-7f3a"
    }
}
