package dev.breaker.server.syncapi.transcriptions

// A strict reader for one JSON object whose member values are strings, numbers or the literals
// true, false and null. It refuses nested objects and arrays, duplicate keys, bytes after the
// object, bad escapes and raw control characters. It is hand-written: no serialization library.

internal sealed class SyncJsonValue

internal class SyncJsonText(val value: String) : SyncJsonValue()

// The raw characters of a number. The reader does not judge them; the body check does.
internal class SyncJsonNumeral(val raw: String) : SyncJsonValue()

internal object SyncJsonLiteral : SyncJsonValue()

// The members of the object, or null when the text is not exactly one such object.
internal fun readSyncJsonObject(text: String): Map<String, SyncJsonValue>? = SyncJsonReader(text).readWhole()

private class SyncJsonMalformed : Exception()

private class SyncJsonReader(private val text: String) {
    private var pos = 0

    fun readWhole(): Map<String, SyncJsonValue>? {
        return try {
            skipWhitespace()
            val members = readObject()
            skipWhitespace()
            if (pos == text.length) members else null
        } catch (_: SyncJsonMalformed) {
            null
        }
    }

    private fun readObject(): Map<String, SyncJsonValue> {
        expect('{')
        val members = LinkedHashMap<String, SyncJsonValue>()
        skipWhitespace()
        if (peek() == '}') {
            pos++
            return members
        }
        var closed = false
        while (!closed) {
            skipWhitespace()
            val key = readString()
            skipWhitespace()
            expect(':')
            skipWhitespace()
            val value = readValue()
            if (members.containsKey(key)) throw SyncJsonMalformed()
            members[key] = value
            skipWhitespace()
            when (next()) {
                ',' -> {}
                '}' -> { closed = true }
                else -> throw SyncJsonMalformed()
            }
        }
        return members
    }

    private fun readValue(): SyncJsonValue {
        val c = peek()
        return when {
            c == '"' -> SyncJsonText(readString())
            c == '-' || c in '0'..'9' -> SyncJsonNumeral(readNumeral())
            literal("true") -> SyncJsonLiteral
            literal("false") -> SyncJsonLiteral
            literal("null") -> SyncJsonLiteral
            else -> throw SyncJsonMalformed()
        }
    }

    private fun literal(word: String): Boolean {
        if (!text.startsWith(word, pos)) return false
        pos += word.length
        return true
    }

    private fun readNumeral(): String {
        val start = pos
        while (pos < text.length && isNumeralCharacter(text[pos])) pos++
        return text.substring(start, pos)
    }

    private fun isNumeralCharacter(c: Char): Boolean =
        c in '0'..'9' || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E'

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (pos < text.length) {
            val c = text[pos]
            pos++
            if (c == '"') return out.toString()
            if (c == '\\') {
                out.append(readEscape())
            } else if (c < ' ') {
                throw SyncJsonMalformed()
            } else {
                out.append(c)
            }
        }
        throw SyncJsonMalformed()
    }

    private fun readEscape(): Char {
        val c = next()
        return when (c) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readHexQuad()
            else -> throw SyncJsonMalformed()
        }
    }

    private fun readHexQuad(): Char {
        if (pos + 4 > text.length) throw SyncJsonMalformed()
        var code = 0
        for (offset in 0 until 4) {
            val digit = hexValue(text[pos + offset])
            if (digit < 0) throw SyncJsonMalformed()
            code = code * 16 + digit
        }
        pos += 4
        return code.toChar()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun skipWhitespace() {
        while (pos < text.length && isJsonSpace(text[pos])) pos++
    }

    private fun isJsonSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private fun peek(): Char {
        if (pos >= text.length) throw SyncJsonMalformed()
        return text[pos]
    }

    private fun next(): Char {
        val c = peek()
        pos++
        return c
    }

    private fun expect(c: Char) {
        if (next() != c) throw SyncJsonMalformed()
    }
}
