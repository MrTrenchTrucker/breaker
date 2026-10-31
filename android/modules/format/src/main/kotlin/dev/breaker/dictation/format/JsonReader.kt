package dev.breaker.dictation.format

internal object JsonReader {
    internal object JsonNull

    private const val MAX_DEPTH = 16

    fun parse(text: String): Any? {
        val parser = Parser(text)
        val value = parser.parseValue() ?: return null
        if (!parser.atEnd()) return null
        return value
    }

    private class Parser(private val text: String) {
        var pos = 0
        private var depth = 0

        fun atEnd(): Boolean {
            skipWhitespace()
            return pos >= text.length
        }

        fun parseValue(): Any? {
            skipWhitespace()
            if (pos >= text.length) return null
            depth++
            if (depth > MAX_DEPTH) return null
            return try {
                when (text[pos]) {
                    '{' -> parseObject()
                    '[' -> parseArray()
                    '"' -> parseString()
                    't', 'f' -> parseBool()
                    'n' -> parseNull()
                    else -> parseNumber()
                }
            } finally {
                depth--
            }
        }

        private fun skipWhitespace() {
            while (pos < text.length && text[pos].isWhitespace()) pos++
        }

        private fun parseObject(): Map<String, Any?>? {
            pos++ // {
            skipWhitespace()
            val map = mutableMapOf<String, Any?>()
            if (pos < text.length && text[pos] == '}') { pos++; return map }
            while (true) {
                skipWhitespace()
                val key = parseString() ?: return null
                skipWhitespace()
                if (pos >= text.length || text[pos] != ':') return null
                pos++ // :
                val value = parseValue() ?: return null
                map[key] = value
                skipWhitespace()
                if (pos >= text.length) return null
                if (text[pos] == '}') { pos++; return map }
                if (text[pos] != ',') return null
                pos++ // ,
            }
        }

        private fun parseArray(): List<Any?>? {
            pos++ // [
            skipWhitespace()
            val list = mutableListOf<Any?>()
            if (pos < text.length && text[pos] == ']') { pos++; return list }
            while (true) {
                val value = parseValue() ?: return null
                list.add(value)
                skipWhitespace()
                if (pos >= text.length) return null
                if (text[pos] == ']') { pos++; return list }
                if (text[pos] != ',') return null
                pos++ // ,
            }
        }

        private fun parseString(): String? {
            if (pos >= text.length || text[pos] != '"') return null
            pos++ // opening quote
            val sb = StringBuilder()
            while (pos < text.length) {
                val c = text[pos]
                if (c == '"') { pos++; return sb.toString() }
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
                            sb.append(code.toChar())
                            pos += 4
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

        private fun parseBool(): Any? {
            if (text.startsWith("true", pos)) { pos += 4; return true }
            if (text.startsWith("false", pos)) { pos += 5; return false }
            return null
        }

        private fun parseNull(): Any? {
            if (text.startsWith("null", pos)) { pos += 4; return JsonNull }
            return null
        }

        private fun parseNumber(): Any? {
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
            return text.substring(start, pos).toDoubleOrNull()
        }
    }
}
