package dev.breaker.dictation.auth.client

import dev.breaker.dictation.core.model.AuthSession
import dev.breaker.dictation.core.model.TokenScope
import dev.breaker.dictation.core.model.UserRole

/**
 * The text form of a signed-in session, as the vault keeps it.
 *
 * The text is one JSON object with the keys user_id, username, token, role, scopes and server,
 * with no whitespace. The server is the address the session was made with, kept exactly as
 * given. [decode] is strict: any text outside that shape returns null. Nothing is thrown and no
 * part of the input is echoed.
 */
internal object SessionRecord {
    /** The longest text [decode] reads and [encode] writes, in UTF-16 units (the String length). */
    private const val MAX_CHARS = 16384
    private const val HEX_DIGITS = "0123456789abcdef"

    /**
     * Writes the session and the server address it was made with as record text. The server may
     * not be empty and the whole record may not be longer than [MAX_CHARS]; the failure message
     * carries no input.
     */
    fun encode(session: AuthSession, server: String): String {
        require(server.isNotEmpty())
        val out = StringBuilder()
        out.append("{\"user_id\":")
        writeString(out, session.userId)
        out.append(",\"username\":")
        writeString(out, session.username)
        out.append(",\"token\":")
        writeString(out, session.token)
        out.append(",\"role\":\"")
        out.append(roleName(session.role))
        out.append("\",\"scopes\":[")
        out.append(scopeArray(session.scopes))
        out.append("],\"server\":")
        writeString(out, server)
        out.append('}')
        val text = out.toString()
        require(text.length <= MAX_CHARS) { "the session record is too long" }
        return text
    }

    /** Reads a record, or returns null for any text that is not exactly one well-formed record. */
    fun decode(text: String): StoredSession? {
        if (text.length > MAX_CHARS) return null
        return try {
            RecordParser(text).parseSession()
        } catch (refused: Refused) {
            null
        } catch (refusal: IllegalArgumentException) {
            null
        }
    }

    private fun roleName(role: UserRole): String = when (role) {
        UserRole.ADMIN -> "admin"
        UserRole.USER -> "user"
    }

    /** The scope names in enum order (transcribe, then admin), whatever order the set has. */
    private fun scopeArray(scopes: Set<TokenScope>): String {
        var transcribe = false
        var admin = false
        for (scope in scopes) {
            when (scope) {
                TokenScope.TRANSCRIBE -> { transcribe = true }
                TokenScope.ADMIN -> { admin = true }
            }
        }
        val out = StringBuilder()
        if (transcribe) out.append("\"transcribe\"")
        if (transcribe && admin) out.append(',')
        if (admin) out.append("\"admin\"")
        return out.toString()
    }

    /** Writes a JSON string: quote, backslash and control characters escaped; a valid pair raw. */
    private fun writeString(out: StringBuilder, value: String) {
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
            if (c.isSurrogate()) {
                writeUnicodeEscape(out, c.code)
            } else {
                writeChar(out, c)
            }
            i++
        }
        out.append('"')
    }

    private fun writeChar(out: StringBuilder, c: Char) {
        val code = c.code
        when {
            c == '"' -> out.append("\\\"")
            c == '\\' -> out.append("\\\\")
            code == 0x08 -> out.append("\\b")
            code == 0x09 -> out.append("\\t")
            code == 0x0A -> out.append("\\n")
            code == 0x0C -> out.append("\\f")
            code == 0x0D -> out.append("\\r")
            code < 0x20 -> writeUnicodeEscape(out, code)
            else -> out.append(c)
        }
    }

    /** Writes a \u escape with four lowercase hex digits. */
    private fun writeUnicodeEscape(out: StringBuilder, code: Int) {
        out.append("\\u")
        out.append(HEX_DIGITS[(code shr 12) and 0x0F])
        out.append(HEX_DIGITS[(code shr 8) and 0x0F])
        out.append(HEX_DIGITS[(code shr 4) and 0x0F])
        out.append(HEX_DIGITS[code and 0x0F])
    }
}

/**
 * A session read back from the vault, with the server address it was made with. It is a plain
 * class, and [toString] prints no part of the session.
 */
internal class StoredSession(val session: AuthSession, val server: String) {
    override fun toString(): String = "StoredSession"
}

/** Raised inside the parser to refuse a record. It has no message, so no input is echoed. */
private class Refused : RuntimeException()

/** A parsed member value: a string, or an array of strings. */
private sealed class Member
private class TextMember(val text: String) : Member()
private class ListMember(val entries: List<String>) : Member()

/** Reads one record. Every failure throws [Refused]; no character is read without a bounds check. */
private class RecordParser(private val text: String) {
    private var pos = 0

    fun parseSession(): StoredSession {
        expect('{')
        val members = LinkedHashMap<String, Member>()
        while (true) {
            val key = readString(allowEscapes = false)
            if (members.containsKey(key)) throw Refused()
            expect(':')
            members[key] = readMember()
            val sep = nextChar()
            if (sep == ',') continue
            if (sep == '}') break
            throw Refused()
        }
        if (pos != text.length) throw Refused()
        return build(members)
    }

    private fun build(members: Map<String, Member>): StoredSession {
        if (members.size != 6) throw Refused()
        val userId = (members["user_id"] as? TextMember)?.text ?: throw Refused()
        val username = (members["username"] as? TextMember)?.text ?: throw Refused()
        val bearer = (members["token"] as? TextMember)?.text ?: throw Refused()
        val roleName = (members["role"] as? TextMember)?.text ?: throw Refused()
        val scopeNames = (members["scopes"] as? ListMember)?.entries ?: throw Refused()
        val server = (members["server"] as? TextMember)?.text ?: throw Refused()
        if (server.isEmpty()) throw Refused()
        val role = roleOf(roleName)
        val scopes = LinkedHashSet<TokenScope>()
        for (name in scopeNames) {
            if (!scopes.add(scopeOf(name))) throw Refused()
        }
        return StoredSession(AuthSession(userId, username, bearer, role, scopes), server)
    }

    private fun roleOf(name: String): UserRole = when (name) {
        "admin" -> UserRole.ADMIN
        "user" -> UserRole.USER
        else -> throw Refused()
    }

    private fun scopeOf(name: String): TokenScope = when (name) {
        "transcribe" -> TokenScope.TRANSCRIBE
        "admin" -> TokenScope.ADMIN
        else -> throw Refused()
    }

    private fun readMember(): Member = when (peekChar()) {
        '"' -> TextMember(readString(allowEscapes = true))
        '[' -> ListMember(readList())
        else -> throw Refused()
    }

    private fun readList(): List<String> {
        expect('[')
        val entries = ArrayList<String>()
        if (peekChar() == ']') {
            pos++
            return entries
        }
        while (true) {
            entries.add(readString(allowEscapes = true))
            val after = nextChar()
            if (after == ',') continue
            if (after == ']') return entries
            throw Refused()
        }
    }

    private fun readString(allowEscapes: Boolean): String {
        expect('"')
        val out = StringBuilder()
        while (true) {
            val c = nextChar()
            when {
                c == '"' -> return out.toString()
                c == '\\' -> {
                    if (!allowEscapes) throw Refused()
                    readEscape(out)
                }
                c.isHighSurrogate() -> {
                    if (pos >= text.length || !text[pos].isLowSurrogate()) throw Refused()
                    out.append(c)
                    out.append(text[pos])
                    pos++
                }
                c.isLowSurrogate() -> throw Refused()
                c.code < 0x20 -> throw Refused()
                else -> out.append(c)
            }
        }
    }

    private fun readEscape(out: StringBuilder) {
        when (nextChar()) {
            '"' -> out.append('"')
            '\\' -> out.append('\\')
            '/' -> out.append('/')
            'b' -> out.append('\u0008')
            'f' -> out.append('\u000C')
            'n' -> out.append('\n')
            'r' -> out.append('\r')
            't' -> out.append('\t')
            'u' -> out.append(readHex4().toChar())
            else -> throw Refused()
        }
    }

    private fun readHex4(): Int {
        var value = 0
        var n = 0
        while (n < 4) {
            value = value * 16 + hexValue(nextChar())
            n++
        }
        return value
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c.code - '0'.code
        in 'a'..'f' -> c.code - 'a'.code + 10
        in 'A'..'F' -> c.code - 'A'.code + 10
        else -> throw Refused()
    }

    private fun expect(c: Char) {
        if (nextChar() != c) throw Refused()
    }

    private fun peekChar(): Char {
        if (pos >= text.length) throw Refused()
        return text[pos]
    }

    private fun nextChar(): Char {
        val c = peekChar()
        pos++
        return c
    }
}
