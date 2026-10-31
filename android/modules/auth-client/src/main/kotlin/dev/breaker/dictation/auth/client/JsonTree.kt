package dev.breaker.dictation.auth.client

private const val MAX_DEPTH = 4

/** A minimal hand-rolled JSON value tree (SyncBodyCodec style): no third-party library, integers only. */
internal sealed class JsonValue {
    data class Str(val s: String) : JsonValue()
    data class Num(val l: Long) : JsonValue() // integers only, no floats
    data object True : JsonValue()
    data object False : JsonValue()
    data object Null : JsonValue()
    data class Obj(val map: Map<String, JsonValue>) : JsonValue()
    data class Arr(val items: List<JsonValue>) : JsonValue()

    companion object {
        // The companion calls it directly, so this entry point is not private.
        fun parse(text: String): JsonValue = Parser(text).parseText()

        private class Parser(private val s: String) {
            var pos = 0
            private fun ws() { while (pos < s.length && s[pos].isWhitespace()) pos++ }

            // Not private so the companion object above can call it; accepts the single
            // object, then refuses any trailing bytes that are not whitespace.
            fun parseText(): JsonValue {
                val value = parseObject(1)
                ws()
                if (pos < s.length) fail("trailing text at $pos")
                return value
            }


            private fun check(cond: Boolean, want: String) { if (!cond) throw AuthDecodeException("TYPE", want) }
            private fun fail(msg: String): Nothing = throw AuthDecodeException("TYPE", msg)
            private fun expect(ch: Char, lit: String? = null) {
                if (pos >= s.length || s[pos] != ch) fail(if (lit != null) "expected '$lit'" else "expected '$ch'")
                pos++
            }

            private fun peek(): Char = if (pos >= s.length) fail("unexpected end of input") else s[pos]

            private fun parseObject(depth: Int): JsonValue {
                ws(); check(peek() == '{', "expected object at start")
                pos++
                val fields = HashMap<String, JsonValue>()
                ws()
                if (peek() == '}') { pos++; return Obj(emptyMap()) }
                while (true) {
                    ws(); check(peek() == '\"', "expected string key")
                    val k = parseString()
                    ws(); check(peek() == ':', "expected ':' after key")
                    pos++
                    if (fields.containsKey(k)) fail("duplicate key '$k' in object")
                    fields[k] = parseValue(depth)
                    ws()
                    when (peek()) {
                        ',' -> pos++
                        '}' -> { pos++; break }
                        else -> fail("expected ',' or '}' at $pos")
                    }
                }
                return Obj(fields)
            }

            private fun parseArray(depth: Int): JsonValue {
                ws(); check(peek() == '[', "expected array start")
                pos++
                val items = ArrayList<JsonValue>()
                ws()
                if (peek() == ']') { pos++; return Arr(emptyList()) }
                while (true) {
                    items.add(parseValue(depth))
                    ws()
                    when (peek()) {
                        ',' -> pos++
                        ']' -> { pos++; break }
                        else -> fail("expected ',' or ']' at $pos")
                    }
                }
                return Arr(items)
            }

            private fun parseValue(depth: Int): JsonValue {
                ws()
                return when (val ch = peek()) {
                    '{' -> if (depth + 1 > MAX_DEPTH) fail("container would be at level ${depth + 1}, cap is $MAX_DEPTH") else parseObject(depth + 1)
                    '[' -> if (depth + 1 > MAX_DEPTH) fail("container would be at level ${depth + 1}, cap is $MAX_DEPTH") else parseArray(depth + 1)
                    '\"' -> JsonValue.Str(parseString())
                    't' -> consumeLiteral("true", JsonValue.True)
                    'f' -> consumeLiteral("false", JsonValue.False)
                    'n' -> consumeLiteral("null", JsonValue.Null)
                    else -> if (ch.isDigit() || ch == '-') parseInt() else fail("unexpected token '$ch' at ${pos - 1}")
                }
            }

            private fun consumeLiteral(lit: String, value: JsonValue): JsonValue {
                for (j in lit.indices) {
                    if (pos >= s.length || s[pos] != lit[j]) fail("bad literal '$lit'")
                    pos++
                }
                return value
            }

            private fun parseString(): String {
                check(peek() == '\"', "expected string start")
                pos++
                val sb = StringBuilder()
                while (pos < s.length) {
                    val c = s[pos++]
                    when {
                        c == '\"' -> return sb.toString()
                        c == '\\' -> {
                            if (pos >= s.length) fail("bad escape")
                            when (val e = s[pos++]) {
                                'n' -> sb.append('\n'); 't' -> sb.append('\t')
                                '"' -> sb.append('"'); '\\' -> sb.append('\\')
                                '/' -> sb.append('/')
                                'b' -> sb.append('\u0008')
                                'f' -> sb.append('\u000C')
                                'r' -> sb.append('\r')
                                'u' -> {
                                    if (pos + 4 > s.length) fail("bad escape")
                                    val sub = s.substring(pos, pos + 4)
                                    var code = 0
                                    for (ch in sub) {
                                        val d = Character.digit(ch, 16)
                                        if (d < 0) fail("unsupported escape '\\u" + sub + "'")
                                        code = code * 16 + d
                                    }
                                    pos += 4
                                    sb.append(code.toChar())
                                }
                                else -> fail("unsupported escape '\\$e'")
                            }
                        }
                        c.code < 0x20 -> fail("unescaped control character")
                        else -> sb.append(c)
                    }
                }
                fail("unterminated string")
            }

            private fun parseInt(): JsonValue {
                val start = pos
                if (pos < s.length && s[pos] == '-') pos++
                val digits = pos
                while (pos < s.length && s[pos].isDigit()) pos++
                if (pos == digits) fail("invalid integer at $start")
                return Num(s.substring(start, pos).toLongOrNull() ?: fail("invalid integer at $start"))
            }
        }
    }
}

/** A decode/parse failure; the family lets a caller distinguish which check was wrong. */
internal data class AuthDecodeException(val family: String, val detail: String) : RuntimeException(detail) {
    override fun toString(): String = "AuthDecodeException(${family}, $detail)"
}
