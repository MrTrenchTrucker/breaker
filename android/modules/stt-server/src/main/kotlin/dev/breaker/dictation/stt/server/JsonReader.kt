package dev.breaker.dictation.stt.server

/**
 * One parsed JSON value. Objects keep their fields, numbers keep the exact text the
 * input had until the decoder reads them, and a JSON null is the [Null] object.
 */
internal sealed interface JsonValue {
    /** A JSON object. Keys are unique, because the reader refuses a duplicate key. */
    data class Obj(val fields: Map<String, JsonValue>) : JsonValue

    /** A JSON array, items in the order they were written. */
    data class Arr(val items: List<JsonValue>) : JsonValue

    /** A JSON string, after its escapes are decoded. */
    data class Str(val value: String) : JsonValue

    /** A JSON number, kept as the text it had, for example "1.5e3". */
    data class Num(val text: String) : JsonValue

    /** A JSON true or false. */
    data class Bool(val value: Boolean) : JsonValue

    /** A JSON null. */
    object Null : JsonValue
}

/**
 * Raised when the input is not one strict RFC 8259 value. The message is a fixed
 * sentence plus a character offset; it never carries any of the input text.
 */
internal class JsonSyntaxException(message: String) : Exception(message)

/**
 * A strict, bounded JSON reader for the server's job replies.
 *
 * It accepts exactly one value, with only space, tab, CR or LF around it. It refuses
 * comments, trailing commas, single quotes, a leading plus sign, leading zeros, NaN,
 * Infinity, a byte order mark, duplicate keys, lone surrogate escapes, raw control
 * characters inside strings, and nesting deeper than [MAX_DEPTH]. The depth bound also
 * keeps the recursion short, so a hostile body cannot overflow the stack.
 */
internal object JsonReader {
    /** Deepest allowed nesting of objects and arrays. */
    const val MAX_DEPTH: Int = 16

    /** Parses [text] as one JSON value, or throws [JsonSyntaxException]. */
    fun parse(text: String): JsonValue = Parser(text).parseDocument()
}

/** Single-pass recursive descent over [text]. It keeps only the read position. */
private class Parser(private val text: String) {
    private var pos = 0

    fun parseDocument(): JsonValue {
        val value = value(0)
        skipSpace()
        if (pos != text.length) fail()
        return value
    }

    /** Reads one value. [depth] is the depth of the container this value sits in. */
    private fun value(depth: Int): JsonValue {
        skipSpace()
        return when (peekOrNull()) {
            '{' -> obj(depth + 1)
            '[' -> arr(depth + 1)
            '"' -> JsonValue.Str(string())
            't' -> literal("true", JsonValue.Bool(true))
            'f' -> literal("false", JsonValue.Bool(false))
            'n' -> literal("null", JsonValue.Null)
            else -> number()
        }
    }

    private fun obj(depth: Int): JsonValue {
        checkDepth(depth)
        pos++
        val fields = LinkedHashMap<String, JsonValue>()
        skipSpace()
        if (peekOrNull() == '}') {
            pos++
            return JsonValue.Obj(fields)
        }
        var more = true
        while (more) {
            skipSpace()
            if (peekOrNull() != '"') fail()
            val key = string()
            if (fields.containsKey(key)) fail()
            skipSpace()
            expect(':')
            fields[key] = value(depth)
            skipSpace()
            val c = next()
            if (c == '}') {
                more = false
            } else if (c != ',') {
                fail()
            }
        }
        return JsonValue.Obj(fields)
    }

    private fun arr(depth: Int): JsonValue {
        checkDepth(depth)
        pos++
        val items = ArrayList<JsonValue>()
        skipSpace()
        if (peekOrNull() == ']') {
            pos++
            return JsonValue.Arr(items)
        }
        var more = true
        while (more) {
            items.add(value(depth))
            skipSpace()
            val c = next()
            if (c == ']') {
                more = false
            } else if (c != ',') {
                fail()
            }
        }
        return JsonValue.Arr(items)
    }

    private fun checkDepth(depth: Int) {
        if (depth > JsonReader.MAX_DEPTH) fail()
    }

    private fun string(): String {
        expect('"')
        val out = StringBuilder()
        var closed = false
        while (!closed) {
            val c = next()
            if (c == '"') {
                closed = true
            } else if (c == '\\') {
                escape(out)
            } else if (c < ' ') {
                fail()
            } else {
                out.append(c)
            }
        }
        return out.toString()
    }

    private fun escape(out: StringBuilder) {
        when (next()) {
            '"' -> out.append('"')
            '\\' -> out.append('\\')
            '/' -> out.append('/')
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> unicodeEscape(out)
            else -> fail()
        }
    }

    /** A high surrogate must be followed by a low one; a lone low surrogate is refused. */
    private fun unicodeEscape(out: StringBuilder) {
        val first = hex4()
        if (first in 0xDC00..0xDFFF) fail()
        if (first in 0xD800..0xDBFF) {
            expect('\\')
            expect('u')
            val second = hex4()
            if (second !in 0xDC00..0xDFFF) fail()
            out.append(first.toChar()).append(second.toChar())
        } else {
            out.append(first.toChar())
        }
    }

    private fun hex4(): Int {
        var value = 0
        repeat(4) {
            value = value * 16 + hexDigit(next())
        }
        return value
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> fail()
    }

    private fun literal(word: String, value: JsonValue): JsonValue {
        if (!text.startsWith(word, pos)) fail()
        pos += word.length
        return value
    }

    /** RFC 8259 number: optional minus, then 0 or a non-zero digit run, then optional fraction and exponent. */
    private fun number(): JsonValue {
        val start = pos
        if (peekOrNull() == '-') pos++
        val lead = peekOrNull()
        if (lead == '0') {
            pos++
            if (isDigit(peekOrNull())) fail()
        } else if (lead != null && lead in '1'..'9') {
            pos++
            skipDigits()
        } else {
            fail()
        }
        if (peekOrNull() == '.') {
            pos++
            requireDigits()
        }
        if (peekOrNull() == 'e' || peekOrNull() == 'E') {
            pos++
            if (peekOrNull() == '+' || peekOrNull() == '-') pos++
            requireDigits()
        }
        return JsonValue.Num(text.substring(start, pos))
    }

    private fun requireDigits() {
        if (!isDigit(peekOrNull())) fail()
        skipDigits()
    }

    private fun skipDigits() {
        while (isDigit(peekOrNull())) pos++
    }

    private fun isDigit(c: Char?): Boolean = c != null && c in '0'..'9'

    private fun skipSpace() {
        while (pos < text.length && isSpace(text[pos])) pos++
    }

    private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\r' || c == '\n'

    private fun peekOrNull(): Char? = if (pos < text.length) text[pos] else null

    private fun next(): Char {
        val c = peekOrNull() ?: fail()
        pos++
        return c
    }

    private fun expect(expected: Char) {
        if (peekOrNull() != expected) fail()
        pos++
    }

    /** The only message this reader produces: fixed text plus the offset, never input text. */
    private fun fail(): Nothing = throw JsonSyntaxException("JSON text is not valid at character $pos")
}
