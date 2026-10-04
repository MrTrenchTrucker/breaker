package dev.breaker.dictation.ui.gate

/*
 * Turning source text into the code the rules read.
 *
 * A rule about references is about code, so comments and string literals have to
 * go before a scan runs: a framework name in a comment is a sentence and a
 * framework name in a string is content, and neither is a reference the compiler
 * resolves. Stripping them also lets a gate test name the shapes it forbids
 * without becoming an instance of them.
 *
 * The subtlety is a `${...}` hole. A hole is code inside a literal, so it is the
 * one part that is kept. Blanking it along with the rest of the literal would let
 * a line like "API key: ${settings.apiKeyRef}" satisfy a rule about whether the
 * reference is read while still putting the reference on the display, which is
 * the exact failure the reference rules exist to catch.
 */

/**
 * The lines of [text] that are code rather than prose or data.
 *
 * Comments and string literals are removed, and the interpolation holes inside
 * literals are kept, because a hole is code: it resolves the reference it names
 * and writes the value into the string.
 *
 * Literals go first, and the order is load-bearing. A line comment or a block
 * comment can be written inside a literal, and a URL in a help string is the
 * ordinary case. A comment stripper reading code has no way to tell that from a
 * real comment, so it deletes the rest of the line before any rule reads it. A
 * hex colour, an unguarded reference to a secret or a framework call can hide
 * behind one and the gates report a clean tree. Blanking the literal text first
 * takes the comment markers out of the text a comment stripper ever sees, so a
 * marker the compiler treats as content can only ever blank content.
 */
internal fun withoutCommentsAndStrings(text: String): String =
    stripStringLiterals(text).let { code -> withoutComments(code) }

/**
 * [text] with its comments removed and its string literals left in place.
 *
 * A rule about a literal value has to read string literals: a colour written as
 * `"#1E7A46"` is a string, and stripping strings before scanning for one would
 * make the rule unsatisfiable and quietly pass. Comments go the other way and are
 * always removed, because a shape quoted in a comment is a sentence about the
 * shape rather than the shape.
 */
internal fun withoutComments(text: String): String {
    val kept = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        index = when {
            text.startsWith("//", index) -> endOfLine(text, index)
            text.startsWith("/*", index) -> endOf(text, index + 2, "*/")
            else -> {
                kept.append(text[index])
                index + 1
            }
        }
    }
    return kept.toString()
}

/**
 * [text] with the text of its string literals blanked out and everything else kept.
 *
 * Only the holes are copied through; the rest of the literal goes, which is what
 * keeps plain literal text invisible to a rule about code. Line breaks inside a
 * literal are never consumed by the walk, so every line number still names the same
 * line it named before.
 */
private fun stripStringLiterals(text: String): String {
    val kept = StringBuilder(text.length)
    var index = 0
    while (index < text.length) {
        index = when {
            text.startsWith("\"\"\"", index) -> blankLiteral(kept, text, index + 3, "\"\"\"", false)
            text[index] == '"' -> blankLiteral(kept, text, index + 1, "\"", true)
            else -> {
                kept.append(text[index])
                index + 1
            }
        }
    }
    return kept.toString()
}

/**
 * Blanks the literal running from [from] to [close], keeping every `${...}` hole
 * in it, and returns the index just past the close.
 *
 * [escaped] tells a regular literal from a raw one: in a regular literal a
 * backslash hides the next character and a newline ends the literal, and in a raw
 * one neither holds. Holes are the same in both, which is why this is one path.
 */
private fun blankLiteral(
    kept: StringBuilder,
    text: String,
    from: Int,
    close: String,
    escaped: Boolean,
): Int {
    var index = from
    while (index < text.length) {
        when {
            escaped && text[index] == '\\' -> index += 2
            escaped && text[index] == '\n' -> return index
            text.startsWith(close, index) -> return index + close.length
            text.startsWith("\${", index) -> index = keepHole(kept, text, index + 2)
            else -> index++
        }
    }
    return text.length
}

/**
 * Copies the code inside a hole running from [from] to its closing brace into
 * [kept], and returns the index just past that brace.
 *
 * Braces are counted rather than searched for, and a literal inside the hole is
 * passed over whole, so a close brace or a nested string inside one ends neither
 * the hole nor the literal enclosing it. An unterminated hole runs to the end of
 * the text rather than swallowing the scan.
 */
private fun keepHole(kept: StringBuilder, text: String, from: Int): Int {
    var depth = 1
    var index = from
    kept.append("\${")
    while (index < text.length && depth > 0) {
        when {
            text[index] == '"' -> {
                val end = endOfQuoted(text, index)
                kept.append(text, index, end)
                index = end
            }
            text[index] == '{' -> {
                depth++
                kept.append(text[index++])
            }
            text[index] == '}' -> {
                depth--
                if (depth > 0) {
                    kept.append(text[index++])
                } else {
                    index++
                }
            }
            else -> kept.append(text[index++])
        }
    }
    kept.append('}')
    return index
}

/** The index just past the end of the line [from] starts on. */
private fun endOfLine(text: String, from: Int): Int =
    text.indexOf('\n', from).let { if (it < 0) text.length else it }

/** The index just past the [close] that ends the run opening at [from]. */
private fun endOf(text: String, from: Int, close: String): Int {
    val found = text.indexOf(close, from)
    return if (found < 0) text.length else found + close.length
}

/** The index just past the closing quote of the literal starting at [from]. */
private fun endOfQuoted(text: String, from: Int): Int {
    var index = from + 1
    while (index < text.length) {
        when (text[index]) {
            '\\' -> index += 2
            '"' -> return index + 1
            '\n' -> return index
            else -> index++
        }
    }
    return text.length
}
