package dev.breaker.server.syncapi.sync

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/**
 * One push body. Its text form names the client id and the key version only: the encrypted text
 * is never printed through this class.
 */
internal data class PushRequest(val clientId: String, val keyVersion: Int, val encrypted: String) {
    override fun toString(): String = "PushRequest(clientId=$clientId, keyVersion=$keyVersion)"
}

/**
 * Reads the one JSON object a push body may be: exactly the keys `client_id`, `key_version` and
 * `encrypted`, each once, with a string, an integer and a non-empty string. The bytes must be
 * UTF-8 without a byte order mark, and nothing but whitespace may follow the object. Returns null
 * for every refusal; the input text is not kept in any refusal.
 */
internal fun parsePushRequest(raw: ByteArray): PushRequest? {
    val text = decodeUtf8OrNull(raw) ?: return null
    if (text.startsWith('\uFEFF')) return null
    return try {
        PushReader(text).readObject()
    } catch (refusal: PushRefusal) {
        null
    }
}

private fun decodeUtf8OrNull(raw: ByteArray): String? {
    val decoder = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
    return try {
        decoder.decode(ByteBuffer.wrap(raw)).toString()
    } catch (refusal: CharacterCodingException) {
        null
    }
}

/** Thrown inside the reader to stop; it carries no input text. */
private class PushRefusal : RuntimeException("sync push body refused")

private const val CLIENT_ID_KEY = "client_id"
private const val KEY_VERSION_KEY = "key_version"
private const val ENCRYPTED_KEY = "encrypted"

private class PushReader(private val text: String) {
    private var pos = 0
    private var clientId: String? = null
    private var keyVersion: Int? = null
    private var encrypted: String? = null

    fun readObject(): PushRequest {
        skipWhitespace()
        expect('{')
        skipWhitespace()
        if (peek() == '}') {
            pos++
        } else {
            while (true) {
                skipWhitespace()
                val key = readString()
                skipWhitespace()
                expect(':')
                skipWhitespace()
                readMember(key)
                skipWhitespace()
                when (next()) {
                    ',' -> continue
                    '}' -> break
                    else -> refuse()
                }
            }
        }
        skipWhitespace()
        if (pos != text.length) refuse()
        return PushRequest(clientId ?: refuse(), keyVersion ?: refuse(), encrypted ?: refuse())
    }

    private fun readMember(key: String) {
        when (key) {
            CLIENT_ID_KEY -> {
                if (clientId != null) refuse()
                clientId = readStringValue()
            }
            KEY_VERSION_KEY -> {
                if (keyVersion != null) refuse()
                keyVersion = readKeyVersion()
            }
            ENCRYPTED_KEY -> {
                if (encrypted != null) refuse()
                val value = readStringValue()
                if (value.isEmpty()) refuse()
                encrypted = value
            }
            else -> refuse()
        }
    }

    private fun readStringValue(): String {
        if (peek() != '"') refuse()
        return readString()
    }

    /** Reads `0`, or a digit string with no leading zero that fits in an Int: no sign, fraction or exponent. */
    private fun readKeyVersion(): Int {
        val start = pos
        while (pos < text.length && text[pos] in '0'..'9') pos++
        val digits = text.substring(start, pos)
        if (digits.isEmpty()) refuse()
        if (digits.length > 1 && digits[0] == '0') refuse()
        val value = digits.toLongOrNull() ?: refuse()
        if (value > Int.MAX_VALUE) refuse()
        return value.toInt()
    }

    private fun readString(): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            val c = next()
            when {
                c == '"' -> return out.toString()
                c == '\\' -> appendEscape(out)
                c.code < 0x20 -> refuse()
                else -> out.append(c)
            }
        }
    }

    private fun appendEscape(out: StringBuilder) {
        when (next()) {
            '"' -> out.append('"')
            '\\' -> out.append('\\')
            '/' -> out.append('/')
            'b' -> out.append('\b')
            'f' -> out.append('\u000C')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> appendUnicodeEscape(out)
            else -> refuse()
        }
    }

    /** A surrogate escape is kept only as a high and low pair; a lone surrogate is refused. */
    private fun appendUnicodeEscape(out: StringBuilder) {
        val code = hex4()
        if (code in 0xDC00..0xDFFF) refuse()
        if (code in 0xD800..0xDBFF) {
            if (next() != '\\') refuse()
            if (next() != 'u') refuse()
            val low = hex4()
            if (low !in 0xDC00..0xDFFF) refuse()
            out.append(code.toChar()).append(low.toChar())
        } else {
            out.append(code.toChar())
        }
    }

    private fun hex4(): Int {
        var value = 0
        repeat(4) {
            val digit = hexDigit(next())
            if (digit < 0) refuse()
            value = value * 16 + digit
        }
        return value
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    private fun skipWhitespace() {
        while (pos < text.length && (text[pos] == ' ' || text[pos] == '\t' || text[pos] == '\n' || text[pos] == '\r')) {
            pos++
        }
    }

    private fun peek(): Char {
        if (pos >= text.length) refuse()
        return text[pos]
    }

    private fun next(): Char {
        val c = peek()
        pos++
        return c
    }

    private fun expect(c: Char) {
        if (next() != c) refuse()
    }

    private fun refuse(): Nothing = throw PushRefusal()
}
