package dev.breaker.dictation.updater

/** Raised when the input is not one strict JSON value. The message is fixed and never carries input text. */
internal class JsonSyntaxException(message: String) : Exception(message)

/**
 * A strict JSON reader for the update manifest: exactly one value, with only space, tab, CR or LF around it.
 *
 * Limits: it refuses comments, trailing commas, single quotes, a leading plus, NaN, Infinity, a byte order
 * mark, raw control characters inside strings, lone surrogates, unknown escapes, repeated keys at any depth,
 * and nesting deeper than [MAX_DEPTH], with the top-level object as level 1. It has no size cap; the caller
 * applies one before parsing.
 */
internal class JsonParser private constructor(private val text: String) {
    private var pos: Int = 0

    companion object {
        /** The deepest allowed nesting; the top-level object is level 1. */
        internal const val MAX_DEPTH: Int = 4

        /** Parses [text] as one JSON value, or throws [JsonSyntaxException]. */
        fun parse(text: String): JsonValue = JsonParser(text).parseDocument()
    }

    private fun parseDocument(): JsonValue {
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
        if (depth > MAX_DEPTH) fail()
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
            } else if (c in '\uDC00'..'\uDFFF') {
                fail()
            } else if (c in '\uD800'..'\uDBFF') {
                val low = next()
                if (low !in '\uDC00'..'\uDFFF') fail()
                out.append(c).append(low)
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

    /** A high surrogate escape must be followed by a low surrogate escape; a lone low surrogate is refused. */
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

    /** Reads a number by the RFC 8259 grammar and keeps its text. */
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

    private fun fail(): Nothing = throw JsonSyntaxException("JSON text is not valid at character $pos")
}
