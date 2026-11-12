package dev.breaker.dictation.sync

/**
 * One element of the pending list, read completely as JSON. [members] holds the three string members
 * when the element is an object whose members are all allowed, unique and strings, else null. [id] is
 * the text of the one string member named id, if any. [start] and [end] are the element's text range.
 */
internal class RawRow(val members: Map<String, String>?, val id: String?, val start: Int, val end: Int)

/**
 * The strict JSON reader of the pending list. It reads the top level array and parses EVERY element
 * completely (any value kind), so a bad element never hides the elements behind it. Only a fault of
 * the JSON itself, a top level that is not an array, or nesting deeper than [MAX_DEPTH] levels (the
 * top level array is level 1) throws [PendingSealedException]. White space is only space, tab, line
 * feed and carriage return. The depth is checked BEFORE a container is entered, so the reader never
 * recurses more than [MAX_DEPTH] containers deep, whatever the body holds. A refusal never carries text.
 */
internal class PendingJson(private val text: String, private val allowed: Set<String>) {
    private var pos = 0

    /** Reads the whole body as an array and returns its elements in order. */
    fun readList(): List<RawRow> {
        skip()
        if (!at('[')) refuse("not_array")
        pos++
        val rows = ArrayList<RawRow>()
        skip()
        if (at(']')) {
            pos++
            return finish(rows)
        }
        while (true) {
            rows.add(readElement())
            skip()
            if (at(',')) {
                pos++
            } else if (at(']')) {
                pos++
                return finish(rows)
            } else {
                refuse("bad_json")
            }
        }
    }

    /** A 64-bit FNV-1a hash over the raw characters from [from] up to [to], plain Long arithmetic. */
    fun fingerprint(from: Int, to: Int): Long {
        var hash = FNV_OFFSET
        for (i in from until to) {
            hash = (hash xor text[i].code.toLong()) * FNV_PRIME
        }
        return hash
    }

    private fun refuse(family: String): Nothing = throw PendingSealedException(family)

    private fun finish(rows: List<RawRow>): List<RawRow> {
        skip()
        if (pos < text.length) refuse("bad_json")
        return rows
    }

    private fun readElement(): RawRow {
        skip()
        val start = pos
        if (!at('{')) {
            skipValue(ROW_LEVEL)
            return RawRow(null, null, start, pos)
        }
        pos++
        val members = HashMap<String, String>()
        val seen = HashSet<String>()
        var clean = true
        var id: String? = null
        var idRepeated = false
        skip()
        if (at('}')) {
            pos++
        } else {
            while (true) {
                skip()
                val key = readString()
                skip()
                if (!at(':')) refuse("bad_json")
                pos++
                skip()
                if (pos >= text.length) refuse("bad_json")
                var value: String? = null
                if (at('"')) value = readString() else skipValue(VALUE_LEVEL)
                if (key !in allowed || !seen.add(key) || value == null) clean = false
                if (value != null && key == "id") {
                    if (id == null) id = value else idRepeated = true
                }
                if (value != null) members[key] = value
                skip()
                if (at(',')) {
                    pos++
                } else if (at('}')) {
                    pos++
                    break
                } else {
                    refuse("bad_json")
                }
            }
        }
        return RawRow(if (clean) members else null, if (idRepeated) null else id, start, pos)
    }

    private fun skipValue(level: Int) {
        skip()
        if (pos >= text.length) refuse("bad_json")
        when (text[pos]) {
            '"' -> readString()
            '{' -> skipObject(level)
            '[' -> skipArray(level)
            't' -> skipLiteral("true")
            'f' -> skipLiteral("false")
            'n' -> skipLiteral("null")
            else -> skipNumber()
        }
    }

    private fun skipArray(level: Int) {
        if (level > MAX_DEPTH) refuse("too_deep")
        pos++
        skip()
        if (at(']')) {
            pos++
            return
        }
        while (true) {
            skipValue(level + 1)
            skip()
            if (at(',')) {
                pos++
            } else if (at(']')) {
                pos++
                return
            } else {
                refuse("bad_json")
            }
        }
    }

    private fun skipObject(level: Int) {
        if (level > MAX_DEPTH) refuse("too_deep")
        pos++
        skip()
        if (at('}')) {
            pos++
            return
        }
        while (true) {
            skip()
            readString()
            skip()
            if (!at(':')) refuse("bad_json")
            pos++
            skipValue(level + 1)
            skip()
            if (at(',')) {
                pos++
            } else if (at('}')) {
                pos++
                return
            } else {
                refuse("bad_json")
            }
        }
    }

    private fun skipLiteral(word: String) {
        if (!text.startsWith(word, pos)) refuse("bad_json")
        pos += word.length
    }

    private fun skipNumber() {
        if (at('-')) pos++
        if (at('0')) pos++ else skipDigits()
        if (at('.')) {
            pos++
            skipDigits()
        }
        if (at('e') || at('E')) {
            pos++
            if (at('+') || at('-')) pos++
            skipDigits()
        }
    }

    private fun skipDigits() {
        if (pos >= text.length || text[pos] !in '0'..'9') refuse("bad_json")
        while (pos < text.length && text[pos] in '0'..'9') pos++
    }

    private fun readString(): String {
        if (!at('"')) refuse("bad_json")
        pos++
        val out = StringBuilder()
        while (pos < text.length) {
            val c = text[pos]
            pos++
            if (c == '"') return out.toString()
            if (c < ' ') refuse("bad_json")
            if (c == '\\') out.append(readEscape()) else out.append(c)
        }
        refuse("bad_json")
    }

    private fun readEscape(): Char {
        if (pos >= text.length) refuse("bad_json")
        val e = text[pos]
        pos++
        return when (e) {
            '"' -> '"'
            '\\' -> '\\'
            '/' -> '/'
            'b' -> '\b'
            'f' -> '\u000C'
            'n' -> '\n'
            'r' -> '\r'
            't' -> '\t'
            'u' -> readHex4()
            else -> refuse("bad_json")
        }
    }

    private fun readHex4(): Char {
        if (pos + 4 > text.length) refuse("bad_json")
        var code = 0
        for (k in 0 until 4) {
            code = code * 16 + hexValue(text[pos + k])
        }
        pos += 4
        return code.toChar()
    }

    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> refuse("bad_json")
    }

    private fun at(c: Char): Boolean = pos < text.length && text[pos] == c

    private fun skip() {
        while (pos < text.length && isSpace(text[pos])) pos++
    }

    private fun isSpace(c: Char): Boolean = c == ' ' || c == '\t' || c == '\n' || c == '\r'

    private companion object {
        /** The deepest container level accepted; the top level array is level 1. */
        const val MAX_DEPTH = 16
        const val ROW_LEVEL = 2
        const val VALUE_LEVEL = 3
        const val FNV_OFFSET = -3750763034362895579L
        const val FNV_PRIME = 1099511628211L
    }
}
