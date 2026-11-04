package dev.breaker.server.syncapi.auth

// An integer member has at most ten digits, which always fit a Long before the Int bound check.
private const val MAX_INTEGER_DIGITS = 10

// Thrown inside a reader for every refusal. It carries no text from the request.
private class ChangeBodyRefused : Exception("change-password body refused")

internal fun refuse(): Nothing = throw ChangeBodyRefused()

/**
 * The character-level reading steps shared by the strict body readers: one position in one text,
 * strings with their escapes, plain integers, and the four JSON space characters. Every refusal
 * throws, so a reader built on it is wrapped in a try that turns any exception into null.
 */
internal open class StrictJsonCursor(protected val text: String) {
    protected var position = 0

    // A member that is already set is given twice: refuse.
    protected fun <T : Any> once(current: T?, value: T): T {
        if (current != null) refuse()
        return value
    }

    // Plain ASCII digits only, at most ten of them, no extra zero in front, never above Int.MAX_VALUE.
    // A sign, a fraction or an exponent is not read here, so the next step finds it and refuses.
    protected fun readInteger(): Int {
        val start = position
        var value = 0L
        while (position < text.length && text[position] in '0'..'9') {
            value = value * 10L + (text[position] - '0')
            position++
            if (position - start > MAX_INTEGER_DIGITS) refuse()
        }
        val digits = position - start
        if (digits == 0) refuse()
        if (digits > 1 && text[start] == '0') refuse()
        if (value > Int.MAX_VALUE) refuse()
        return value.toInt()
    }

    protected fun readString(): String {
        expect('"')
        val builder = StringBuilder()
        while (position < text.length) {
            val c = text[position]
            position++
            when {
                c == '"' -> return builder.toString()
                c == '\\' -> builder.append(readEscape())
                c.code < 0x20 -> refuse()
                else -> builder.append(c)
            }
        }
        refuse()
    }

    protected fun readEscape(): Char {
        return when (readChar()) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readUnicodeEscape()
            else -> refuse()
        }
    }

    // Exactly four hex digits, no sign, no short form.
    protected fun readUnicodeEscape(): Char {
        var code = 0
        repeat(4) {
            code = code * 16 + hexValue(readChar())
        }
        return code.toChar()
    }

    protected fun hexValue(c: Char): Int {
        return when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> refuse()
        }
    }

    protected fun readChar(): Char {
        if (position >= text.length) refuse()
        val c = text[position]
        position++
        return c
    }

    protected fun expect(c: Char) {
        if (readChar() != c) refuse()
    }

    protected fun skipSpace() {
        while (position < text.length && isJsonSpace(text[position])) position++
    }

    protected fun isJsonSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'
}
