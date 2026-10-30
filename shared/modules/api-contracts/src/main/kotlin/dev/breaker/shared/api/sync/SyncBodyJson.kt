package dev.breaker.shared.api.sync

/** A parsed JSON value, kept only as far as the field rules need it. */
internal sealed class JsonValue {
    class Str(val value: String) : JsonValue()
    class Num(val token: String) : JsonValue()
    class Lit(val word: String) : JsonValue()
    object Nested : JsonValue()
}

private val HEX_DIGITS = "0123456789abcdef"

// The sync body is a flat object of the six scalar keys only (the R1 body description in
// openapi.yaml: v, text, source, model, duration_ms, created_at, "No other key is allowed"). The top object is level 1,
// so any object or array as a member value is refused before it is entered.
private const val MAX_NESTING_DEPTH = 1

/** Writes value as a JSON string into out. Refuses a lone surrogate; writes everything else raw except the escapes. */
internal fun writeJsonString(out: StringBuilder, value: String) {
    out.append('"')
    var i = 0
    while (i < value.length) {
        val c = value[i]
        if (c.isHighSurrogate() && i + 1 < value.length && value[i + 1].isLowSurrogate()) {
            out.append(c)
            out.append(value[i + 1])
            i += 2
            continue
        }
        if (c.isSurrogate()) refuse(SyncBodyFamily.SURROGATE)
        val code = c.code
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            code == 0x08 -> out.append("\\b")
            code == 0x0C -> out.append("\\f")
            code == 0x0A -> out.append("\\n")
            code == 0x0D -> out.append("\\r")
            code == 0x09 -> out.append("\\t")
            code < 0x20 -> out.append("\\u00").append(HEX_DIGITS[code shr 4]).append(HEX_DIGITS[code and 0x0F])
            else -> out.append(c)
        }
        i++
    }
    out.append('"')
}

/** The value of one ASCII hex digit, or -1. */
internal fun hexValue(c: Char): Int = when (c) {
    in '0'..'9' -> c.code - '0'.code
    in 'a'..'f' -> c.code - 'a'.code + 10
    in 'A'..'F' -> c.code - 'A'.code + 10
    else -> -1
}

/**
 * A strict reader of the one JSON object a sync body may be. It checks JSON
 * syntax only; the field rules are applied by the codec.
 */
internal class SyncBodyScanner(private val src: String) {
    private var pos = 0

    fun parseDocument(): LinkedHashMap<String, JsonValue> {
        skipWs()
        if (pos >= src.length) refuse(SyncBodyFamily.TRUNCATED)
        if (src[pos] != '{') refuse(SyncBodyFamily.NOT_OBJECT)
        val obj = parseObject(1)
        skipWs()
        if (pos < src.length) refuse(SyncBodyFamily.TRAILING)
        return obj
    }

    private fun skipWs() {
        while (pos < src.length && (src[pos] == ' ' || src[pos] == '\t' || src[pos] == '\n' || src[pos] == '\r')) {
            pos++
        }
    }

    private fun peek(): Char {
        if (pos >= src.length) refuse(SyncBodyFamily.TRUNCATED)
        return src[pos]
    }

    private fun expect(c: Char) {
        if (peek() != c) refuse(SyncBodyFamily.SYNTAX)
        pos++
    }

    private fun parseObject(depth: Int): LinkedHashMap<String, JsonValue> {
        pos++
        skipWs()
        val members = LinkedHashMap<String, JsonValue>()
        if (peek() == '}') {
            pos++
            return members
        }
        while (true) {
            if (peek() != '"') refuse(SyncBodyFamily.SYNTAX)
            val key = parseString()
            if (members.containsKey(key)) refuse(SyncBodyFamily.DUPLICATE_KEY)
            skipWs()
            expect(':')
            skipWs()
            members[key] = parseValue(depth)
            skipWs()
            val c = peek()
            if (c == ',') {
                pos++
                skipWs()
                continue
            }
            if (c == '}') {
                pos++
                return members
            }
            refuse(SyncBodyFamily.SYNTAX)
        }
    }

    private fun parseValue(depth: Int): JsonValue {
        val c = peek()
        return when {
            c == '"' -> JsonValue.Str(parseString())
            c == '{' -> {
                if (depth + 1 > MAX_NESTING_DEPTH) refuse(SyncBodyFamily.SYNTAX)
                parseObject(depth + 1)
                JsonValue.Nested
            }
            c == '[' -> {
                if (depth + 1 > MAX_NESTING_DEPTH) refuse(SyncBodyFamily.SYNTAX)
                parseArray(depth + 1)
                JsonValue.Nested
            }
            c == 't' -> {
                literal("true")
                JsonValue.Lit("true")
            }
            c == 'f' -> {
                literal("false")
                JsonValue.Lit("false")
            }
            c == 'n' -> {
                literal("null")
                JsonValue.Lit("null")
            }
            c == '-' || c in '0'..'9' -> JsonValue.Num(parseNumberToken())
            else -> refuse(SyncBodyFamily.SYNTAX)
        }
    }

    private fun parseArray(depth: Int) {
        pos++
        skipWs()
        if (peek() == ']') {
            pos++
            return
        }
        while (true) {
            parseValue(depth)
            skipWs()
            val c = peek()
            if (c == ',') {
                pos++
                skipWs()
                continue
            }
            if (c == ']') {
                pos++
                return
            }
            refuse(SyncBodyFamily.SYNTAX)
        }
    }

    private fun literal(word: String) {
        for (ch in word) {
            if (pos >= src.length) refuse(SyncBodyFamily.TRUNCATED)
            if (src[pos] != ch) refuse(SyncBodyFamily.SYNTAX)
            pos++
        }
    }

    private fun parseNumberToken(): String {
        val start = pos
        while (pos < src.length && (src[pos] in '0'..'9' || src[pos] == '+' || src[pos] == '-' || src[pos] == '.' || src[pos] == 'e' || src[pos] == 'E')) {
            pos++
        }
        return src.substring(start, pos)
    }

    private fun parseString(): String {
        pos++
        val sb = StringBuilder()
        while (true) {
            if (pos >= src.length) refuse(SyncBodyFamily.TRUNCATED)
            val c = src[pos]
            if (c == '"') {
                pos++
                return sb.toString()
            }
            if (c == '\\') {
                pos++
                sb.append(parseEscape())
                continue
            }
            if (c.code < 0x20) refuse(SyncBodyFamily.SYNTAX)
            sb.append(c)
            pos++
        }
    }

    private fun parseEscape(): String {
        if (pos >= src.length) refuse(SyncBodyFamily.TRUNCATED)
        val c = src[pos]
        pos++
        return when (c) {
            '"' -> "\""
            '\\' -> "\\"
            '/' -> "/"
            'b' -> "\u0008"
            'f' -> "\u000C"
            'n' -> "\n"
            'r' -> "\r"
            't' -> "\t"
            'u' -> {
                val code = hex4()
                if (code in 0xD800..0xDBFF) {
                    if (pos >= src.length) refuse(SyncBodyFamily.TRUNCATED)
                    if (src[pos] != '\\') refuse(SyncBodyFamily.SURROGATE)
                    pos++
                    if (pos >= src.length) refuse(SyncBodyFamily.TRUNCATED)
                    if (src[pos] != 'u') refuse(SyncBodyFamily.SURROGATE)
                    pos++
                    val low = hex4()
                    if (low !in 0xDC00..0xDFFF) refuse(SyncBodyFamily.SURROGATE)
                    code.toChar().toString() + low.toChar().toString()
                } else if (code in 0xDC00..0xDFFF) {
                    refuse(SyncBodyFamily.SURROGATE)
                } else {
                    code.toChar().toString()
                }
            }
            else -> refuse(SyncBodyFamily.SYNTAX)
        }
    }

    private fun hex4(): Int {
        var value = 0
        repeat(4) {
            if (pos >= src.length) refuse(SyncBodyFamily.TRUNCATED)
            val h = hexValue(src[pos])
            if (h < 0) refuse(SyncBodyFamily.SYNTAX)
            value = value * 16 + h
            pos++
        }
        return value
    }
}
