package dev.breaker.dictation.sync

/** One parsed JSON value. Numbers are kept as their token text. */
internal sealed class JsonValue {
    data class Obj(val map: Map<String, JsonValue>) : JsonValue()
    data class Arr(val list: List<JsonValue>) : JsonValue()
    data class Str(val s: String) : JsonValue()
    data class Num(val token: String) : JsonValue()
    data class Bool(val v: Boolean) : JsonValue()
    object Null : JsonValue()
}

/** Small strict reader: one JSON value into a tree the decoder walks. */
internal object PullPageJson {
    private const val MAX_DEPTH = 8

    /** Parse [text] as one JSON value with nothing but white space after it. */
    fun parse(text: String): JsonValue {
        val parser = Parser(text)
        return parser.parseAll() ?: throw PullPageException("body is not one JSON value")
    }

    private class Parser(private val text: String) {
        var pos = 0

        fun parseAll(): JsonValue? {
            val value = parseValue(0) ?: return null
            skipWhitespace()
            if (!atEnd()) throw PullPageException("trailing text after the value")
            return value
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun atEnd(): Boolean {
            skipWhitespace()
            return pos >= text.length
        }

        private fun parseValue(depth: Int): JsonValue? {
            skipWhitespace()
            if (pos >= text.length) return null
            return when (text[pos]) {
                '{' -> if (depth < MAX_DEPTH) parseObject(depth + 1) else null
                '[' -> if (depth < MAX_DEPTH) parseArray(depth + 1) else null
                '"' -> parseString()
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else -> parseNumber()
            }
        }

        private fun parseObject(depth: Int): JsonValue? {
            pos++ // {
            skipWhitespace()
            val map = mutableMapOf<String, JsonValue>()
            if (pos < text.length && text[pos] == '}') { pos++; return JsonValue.Obj(map) }
            while (true) {
                skipWhitespace()
                val key = parseString()?.s ?: return null
                skipWhitespace()
                if (pos >= text.length || text[pos] != ':') return null
                pos++ // :
                val value = parseValue(depth) ?: return null
                map[key] = value
                skipWhitespace()
                if (pos >= text.length) return null
                if (text[pos] == '}') { pos++; return JsonValue.Obj(map) }
                if (text[pos] != ',') return null
                pos++ // ,
            }
        }

        private fun parseArray(depth: Int): JsonValue? {
            pos++ // [
            skipWhitespace()
            val list = mutableListOf<JsonValue>()
            if (pos < text.length && text[pos] == ']') { pos++; return JsonValue.Arr(list) }
            while (true) {
                val value = parseValue(depth) ?: return null
                list.add(value)
                skipWhitespace()
                if (pos >= text.length) return null
                if (text[pos] == ']') { pos++; return JsonValue.Arr(list) }
                if (text[pos] != ',') return null
                pos++ // ,
            }
        }

        private fun parseString(): JsonValue.Str? {
            if (pos >= text.length || text[pos] != '"') return null
            pos++ // opening quote
            val sb = StringBuilder()
            while (pos < text.length) {
                val c = text[pos]
                if (c == '"') { pos++; return JsonValue.Str(sb.toString()) }
                if (c == '\\') {
                    pos++
                    if (pos >= text.length) return null
                    val esc = text[pos]
                    when (esc) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 >= text.length) return null
                            var code = 0
                            for (k in 1..4) {
                                val digit = Character.digit(text[pos + k], 16)
                                if (digit < 0) return null
                                code = code * 16 + digit
                            }
                            if (code in 0xD800..0xDBFF) {
                                val after = pos + 5
                                if (after + 5 >= text.length ||
                                    text[after] != '\\' || text[after + 1] != 'u') return null
                                var lowCode = 0
                                for (k in 1..4) {
                                    val digit = Character.digit(text[after + k + 1], 16)
                                    if (digit < 0) return null
                                    lowCode = lowCode * 16 + digit
                                }
                                if (lowCode !in 0xDC00..0xDFFF) return null
                                sb.append(code.toChar())
                                sb.append(lowCode.toChar())
                                pos += 4 + 6
                            } else if (code in 0xDC00..0xDFFF) {
                                return null
                            } else {
                                sb.append(code.toChar())
                                pos += 4
                            }
                        }
                        else -> return null
                    }
                } else {
                    sb.append(c)
                }
                pos++
            }
            return null
        }

        private fun parseBool(): JsonValue.Bool? {
            if (text.startsWith("true", pos)) { pos += 4; return JsonValue.Bool(true) }
            if (text.startsWith("false", pos)) { pos += 5; return JsonValue.Bool(false) }
            return null
        }

        private fun parseNull(): JsonValue.Null? {
            if (text.startsWith("null", pos)) { pos += 4; return JsonValue.Null }
            return null
        }

        private fun parseNumber(): JsonValue.Num? {
            val start = pos
            if (pos < text.length && text[pos] == '-') pos++
            val digitsStart = pos
            while (pos < text.length && text[pos] in '0'..'9') pos++
            if (pos == digitsStart) return null
            if (pos < text.length && text[pos] == '.') {
                pos++
                val fracStart = pos
                while (pos < text.length && text[pos] in '0'..'9') pos++
                if (pos == fracStart) return null
            }
            if (pos < text.length && (text[pos] == 'e' || text[pos] == 'E')) {
                pos++
                if (pos < text.length && (text[pos] == '+' || text[pos] == '-')) pos++
                val expStart = pos
                while (pos < text.length && text[pos] in '0'..'9') pos++
                if (pos == expStart) return null
            }
            return JsonValue.Num(text.substring(start, pos))
        }
    }
}
