package dev.breaker.dictation.updater

import dev.breaker.shared.api.UpdateManifest
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

// How deep an object may nest inside the reply. The six fields sit at level 1; an extra member
// the server adds later may nest a little, anything deeper is refused.
private const val MAX_DEPTH = 4

// The most digits a version code may have: it must fit a Long with room to spare.
private const val MAX_CODE_DIGITS = 18

/**
 * Reads the answer of `GET /v1/updates/latest.json`. Hand-written, no library: the reply is one
 * JSON object, read strictly. A duplicate key, text after the object, a byte that is not UTF-8
 * and an unescaped control character are all refused. Members the six fields do not name are
 * read and ignored, so a field the server adds later does not break an older phone.
 *
 * Nothing here throws to the caller and nothing from the text reaches a message: a defect is a
 * null result.
 */
internal object ManifestJson {

    /**
     * The manifest in [bytes], or null when the bytes are not one JSON object that holds
     * `version`, `apk_url`, `sha256`, `signature` and `notes` as strings and `version_code` as a
     * plain whole number of 1 to 18 digits with no sign, no leading zero and a value of at least 1.
     * A blank text field is accepted here: [UpdateDecision] reports a newer release with a blank
     * field as unusable.
     */
    fun decode(bytes: ByteArray): UpdateManifest? {
        val members = readMembers(bytes) ?: return null
        val version = (members["version"] as? Value.Text)?.text ?: return null
        val versionCode = wholeNumber((members["version_code"] as? Value.NumberToken)?.raw) ?: return null
        val apkUrl = (members["apk_url"] as? Value.Text)?.text ?: return null
        val sha256 = (members["sha256"] as? Value.Text)?.text ?: return null
        val signature = (members["signature"] as? Value.Text)?.text ?: return null
        val notes = (members["notes"] as? Value.Text)?.text ?: return null
        return UpdateManifest(version, versionCode, apkUrl, sha256, signature, notes)
    }

    /** The `error` text of an error answer such as the 404 body, or null when there is none. */
    fun errorCode(bytes: ByteArray): String? =
        (readMembers(bytes)?.get("error") as? Value.Text)?.text

    private fun readMembers(bytes: ByteArray): Map<String, Value>? {
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString()
        } catch (_: CharacterCodingException) {
            return null
        }
        return try {
            Reader(text).readDocument()
        } catch (_: BadJson) {
            null
        }
    }

    // A version code is written as 1 to 18 digits, no sign, no fraction, no exponent, no leading
    // zero. That keeps it at least 1, the same floor as the phone's UpdateManifest and the server.
    private fun wholeNumber(raw: String?): Long? {
        if (raw == null || raw.isEmpty() || raw.length > MAX_CODE_DIGITS) return null
        if (raw.any { it < '0' || it > '9' }) return null
        if (raw[0] == '0') return null
        return raw.toLong()
    }

    private sealed class Value {
        class Text(val text: String) : Value()

        class NumberToken(val raw: String) : Value()

        object Other : Value()
    }

    // Thrown inside the reader only; it has no message so no input text can reach one.
    private class BadJson : Exception()

    private class Reader(private val s: String) {
        private var pos = 0

        fun readDocument(): Map<String, Value> {
            skipSpace()
            val members = readObject(1)
            skipSpace()
            if (pos != s.length) throw BadJson()
            return members
        }

        // JSON white space is exactly these four characters.
        private fun skipSpace() {
            while (pos < s.length && (s[pos] == ' ' || s[pos] == '\t' || s[pos] == '\n' || s[pos] == '\r')) pos++
        }

        private fun peek(): Char {
            if (pos >= s.length) throw BadJson()
            return s[pos]
        }

        private fun expect(ch: Char) {
            if (peek() != ch) throw BadJson()
            pos++
        }

        private fun readObject(depth: Int): Map<String, Value> {
            expect('{')
            val members = HashMap<String, Value>()
            skipSpace()
            if (peek() == '}') {
                pos++
                return members
            }
            while (true) {
                skipSpace()
                val key = readString()
                skipSpace()
                expect(':')
                if (members.containsKey(key)) throw BadJson()
                members[key] = readValue(depth)
                skipSpace()
                val next = peek()
                pos++
                if (next == '}') break
                if (next != ',') throw BadJson()
            }
            return members
        }

        private fun readArray(depth: Int) {
            expect('[')
            skipSpace()
            if (peek() == ']') {
                pos++
                return
            }
            while (true) {
                readValue(depth)
                skipSpace()
                val next = peek()
                pos++
                if (next == ']') break
                if (next != ',') throw BadJson()
            }
        }

        private fun readValue(depth: Int): Value {
            skipSpace()
            val c = peek()
            if (c == '{') {
                if (depth + 1 > MAX_DEPTH) throw BadJson()
                readObject(depth + 1)
                return Value.Other
            }
            if (c == '[') {
                if (depth + 1 > MAX_DEPTH) throw BadJson()
                readArray(depth + 1)
                return Value.Other
            }
            if (c == '"') return Value.Text(readString())
            if (c == 't') return literal("true")
            if (c == 'f') return literal("false")
            if (c == 'n') return literal("null")
            if (c == '-' || (c >= '0' && c <= '9')) return Value.NumberToken(readNumber())
            throw BadJson()
        }

        private fun literal(word: String): Value {
            for (ch in word) expect(ch)
            return Value.Other
        }

        // The JSON number grammar: an optional minus, a zero or a digit run that does not start
        // with zero, an optional fraction, an optional exponent. The raw text is kept.
        private fun readNumber(): String {
            val start = pos
            if (peek() == '-') pos++
            if (peek() == '0') {
                pos++
            } else if (peek() >= '1' && peek() <= '9') {
                digits()
            } else {
                throw BadJson()
            }
            if (pos < s.length && s[pos] == '.') {
                pos++
                if (digits() == 0) throw BadJson()
            }
            if (pos < s.length && (s[pos] == 'e' || s[pos] == 'E')) {
                pos++
                if (pos < s.length && (s[pos] == '+' || s[pos] == '-')) pos++
                if (digits() == 0) throw BadJson()
            }
            return s.substring(start, pos)
        }

        private fun digits(): Int {
            val begin = pos
            while (pos < s.length && s[pos] >= '0' && s[pos] <= '9') pos++
            return pos - begin
        }

        private fun readString(): String {
            expect('"')
            val out = StringBuilder()
            while (true) {
                val c = peek()
                pos++
                if (c == '"') break
                if (c.code < 0x20) throw BadJson()
                if (c != '\\') {
                    out.append(c)
                    continue
                }
                val e = peek()
                pos++
                when (e) {
                    '"' -> out.append('"')
                    '\\' -> out.append('\\')
                    '/' -> out.append('/')
                    'b' -> out.append('\b')
                    'f' -> out.append('\u000C')
                    'n' -> out.append('\n')
                    'r' -> out.append('\r')
                    't' -> out.append('\t')
                    'u' -> out.append(hexUnit())
                    else -> throw BadJson()
                }
            }
            return out.toString()
        }

        private fun hexUnit(): Char {
            if (pos + 4 > s.length) throw BadJson()
            var code = 0
            for (i in 0 until 4) {
                val digit = hexValue(s[pos + i])
                if (digit < 0) throw BadJson()
                code = code * 16 + digit
            }
            pos += 4
            return code.toChar()
        }

        private fun hexValue(c: Char): Int =
            when {
                c >= '0' && c <= '9' -> c - '0'
                c >= 'a' && c <= 'f' -> c - 'a' + 10
                c >= 'A' && c <= 'F' -> c - 'A' + 10
                else -> -1
            }
    }
}
