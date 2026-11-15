package dev.breaker.dictation.wiring

import dev.breaker.shared.api.ServiceStatus
import dev.breaker.shared.api.ServiceStatusReply
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * Reads the body of `GET /v1/status` into the shared contract type.
 *
 * A small hand-rolled reader in the shape of the updater's ManifestJson (ManifestJson.kt): this app
 * holds no JSON library and adds none. It is strict and forward-compatible. The top level must be one
 * JSON object; `services` must be an array of objects, each with a string `name` and a boolean `up`;
 * a key may not repeat inside one object; an unknown member is skipped, whatever its value; and
 * nothing may follow the object. A body that is not UTF-8, or breaks any of those rules, reads as no
 * reply (null), which the probe reports as a failure.
 */
internal object ServiceStatusJson {

    /** The deepest nesting the reader follows, so a deep body ends instead of recursing without end. */
    private const val MAX_DEPTH: Int = 8

    /** Reads [body] as the status reply, or null when the body is not exactly one valid reply. */
    fun decode(body: ByteArray): ServiceStatusReply? {
        val reader = Reader(utf8(body) ?: return null)
        return reader.reply()
    }

    /** Strict UTF-8: a byte sequence the charset cannot decode is no reply. */
    private fun utf8(body: ByteArray): String? = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(body))
            .toString()
    } catch (_: CharacterCodingException) {
        null
    }

    private class Reader(private val text: String) {
        private var at: Int = 0

        /** The reply: one object whose `services` member is the array of named services. */
        fun reply(): ServiceStatusReply? {
            space()
            if (!take('{')) return null
            val services = ArrayList<ServiceStatus>()
            space()
            if (!take('}')) {
                val seen = HashSet<String>()
                while (true) {
                    space()
                    val key = string() ?: return null
                    if (!seen.add(key)) return null
                    space()
                    if (!take(':')) return null
                    space()
                    when (key) {
                        "services" -> if (!services(services)) return null
                        else -> if (!skipValue(0)) return null
                    }
                    space()
                    if (take(',')) continue
                    if (take('}')) break
                    return null
                }
            }
            space()
            return if (at == text.length) ServiceStatusReply(services) else null
        }

        /** The `services` array: objects only, no nulls, no other value. */
        private fun services(out: MutableList<ServiceStatus>): Boolean {
            if (!take('[')) return false
            space()
            if (take(']')) return true
            while (true) {
                space()
                val item = entry() ?: return false
                out.add(item)
                space()
                if (take(',')) continue
                return take(']')
            }
        }

        /** One service: a string `name` and a boolean `up`, both required, unknown members skipped. */
        private fun entry(): ServiceStatus? {
            if (!take('{')) return null
            var name: String? = null
            var up: Boolean? = null
            space()
            if (take('}')) return null
            val seen = HashSet<String>()
            while (true) {
                space()
                val key = string() ?: return null
                if (!seen.add(key)) return null
                space()
                if (!take(':')) return null
                space()
                when (key) {
                    "name" -> name = string() ?: return null
                    "up" -> up = boolean() ?: return null
                    else -> if (!skipValue(0)) return null
                }
                space()
                if (take(',')) continue
                if (take('}')) break
                return null
            }
            val named = name ?: return null
            val state = up ?: return null
            return ServiceStatus(named, state)
        }

        /** Skips one value of an unknown member: any JSON value, at most [MAX_DEPTH] deep. */
        private fun skipValue(depth: Int): Boolean {
            if (depth >= MAX_DEPTH) return false
            return when (peek()) {
                '{' -> skipObject(depth + 1)
                '[' -> skipArray(depth + 1)
                '"' -> string() != null
                't' -> literal("true")
                'f' -> literal("false")
                'n' -> literal("null")
                else -> number()
            }
        }

        private fun skipObject(depth: Int): Boolean {
            if (!take('{')) return false
            space()
            if (take('}')) return true
            val seen = HashSet<String>()
            while (true) {
                space()
                val key = string() ?: return false
                if (!seen.add(key)) return false
                space()
                if (!take(':')) return false
                space()
                if (!skipValue(depth)) return false
                space()
                if (take(',')) continue
                return take('}')
            }
        }

        private fun skipArray(depth: Int): Boolean {
            if (!take('[')) return false
            space()
            if (take(']')) return true
            while (true) {
                space()
                if (!skipValue(depth)) return false
                space()
                if (take(',')) continue
                return take(']')
            }
        }

        /** A JSON string, with the escapes of the contract and no raw control character. */
        private fun string(): String? {
            if (!take('"')) return null
            val out = StringBuilder()
            while (true) {
                val c = peek()
                when {
                    c == DONE -> return null
                    c == '"' -> {
                        at++
                        return out.toString()
                    }
                    c == '\\' -> {
                        at++
                        when (peek()) {
                            '"' -> { out.append('"'); at++ }
                            '\\' -> { out.append('\\'); at++ }
                            '/' -> { out.append('/'); at++ }
                            'b' -> { out.append('\b'); at++ }
                            'f' -> { out.append('\u000C'); at++ }
                            'n' -> { out.append('\n'); at++ }
                            'r' -> { out.append('\r'); at++ }
                            't' -> { out.append('\t'); at++ }
                            'u' -> {
                                at++
                                val high = hex4() ?: return null
                                when {
                                    high in 0xD800..0xDBFF -> {
                                        // A high surrogate is only valid in front of a low one.
                                        if (!take('\\')) return null
                                        if (!take('u')) return null
                                        val low = hex4() ?: return null
                                        if (low !in 0xDC00..0xDFFF) return null
                                        out.append(high.toChar()).append(low.toChar())
                                    }
                                    high in 0xDC00..0xDFFF -> return null
                                    else -> out.append(high.toChar())
                                }
                            }
                            else -> return null
                        }
                    }
                    c.code < 0x20 -> return null
                    else -> {
                        out.append(c)
                        at++
                    }
                }
            }
        }

        /** Four hexadecimal digits, as the number they name. */
        private fun hex4(): Int? {
            if (at + 4 > text.length) return null
            var value = 0
            for (i in 0 until 4) {
                val digit = when (val c = text[at + i]) {
                    in '0'..'9' -> c - '0'
                    in 'a'..'f' -> c - 'a' + 10
                    in 'A'..'F' -> c - 'A' + 10
                    else -> return null
                }
                value = (value shl 4) or digit
            }
            at += 4
            return value
        }

        private fun boolean(): Boolean? = when {
            literal("true") -> true
            literal("false") -> false
            else -> null
        }

        /** A JSON number, validated by the grammar rather than read for its value. */
        private fun number(): Boolean {
            if (peek() == '-') at++
            when (peek()) {
                '0' -> at++
                in '1'..'9' -> {
                    at++
                    while (peek() in '0'..'9') at++
                }
                else -> return false
            }
            if (peek() == '.') {
                at++
                if (peek() !in '0'..'9') return false
                while (peek() in '0'..'9') at++
            }
            if (peek() == 'e' || peek() == 'E') {
                at++
                if (peek() == '+' || peek() == '-') at++
                if (peek() !in '0'..'9') return false
                while (peek() in '0'..'9') at++
            }
            return true
        }

        private fun literal(word: String): Boolean {
            if (!text.startsWith(word, at)) return false
            at += word.length
            return true
        }

        private fun take(c: Char): Boolean {
            if (peek() != c) return false
            at++
            return true
        }

        private fun space() {
            while (peek() == ' ' || peek() == '\t' || peek() == '\n' || peek() == '\r') at++
        }

        private fun peek(): Char = if (at < text.length) text[at] else DONE

        private companion object {
            /** The character that means the body ended. */
            const val DONE: Char = '\u0000'
        }
    }
}
