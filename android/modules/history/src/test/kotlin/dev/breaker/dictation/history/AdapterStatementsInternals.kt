package dev.breaker.dictation.history

/**
 * The reading [AdapterStatements] is built on: the three constants its rules share, the lexer, the
 * bracket matcher, and the finder for methods and calls.
 *
 * Everything here is top-level and `internal` rather than a member of [AdapterStatements], because an
 * `object` cannot span files. They are shared by all of its reading, and nothing here is `public`: the
 * module's surface is unchanged.
 */
    private val FUNCTION = Regex("""(?<![\w.])fun\s+[^(]*?(\w+)\s*\(""")
    private const val OUTSIDE_ANY_METHOD = "(outside any method)"
    private const val MASK = '#'
    /** The positions of the body of [function]: between its braces, or after the `=` of an expression body. */
    internal fun bodyRange(lexed: Lexed, function: Method): IntRange {
        val afterParameters = closing(lexed, function.parametersOpen) + 1
        val brace = lexed.masked.indexOf('{', afterParameters)
        val equals = lexed.masked.indexOf('=', afterParameters)
        return when {
            brace in afterParameters until function.end && (equals < 0 || brace < equals) ->
                (brace + 1) until closing(lexed, brace)
            equals in afterParameters until function.end -> (equals + 1) until function.end
            else -> refuse("${function.name} has no body")
        }
    }
    // ── finding methods and calls ────────────────────────────────────────

    internal class Method(val name: String, val start: Int, val parametersOpen: Int, val end: Int)

    /** Every function declaration, each running to the start of the next one. */
    internal fun functionsIn(masked: String): List<Method> {
        val matches = FUNCTION.findAll(masked).toList()
        return matches.mapIndexed { index, match ->
            Method(
                name = match.groupValues[1],
                start = match.range.first,
                parametersOpen = match.range.last,
                end = matches.getOrNull(index + 1)?.range?.first ?: masked.length,
            )
        }
    }

    /** The name of the function that [position] sits in: the last declared before it. */
    internal fun owner(functions: List<Method>, position: Int): String =
        functions.lastOrNull { it.start <= position }?.name ?: OUTSIDE_ANY_METHOD

    internal fun functionNamed(lexed: Lexed, method: String): Method {
        val found = functionsIn(lexed.masked).filter { it.name == method }
        if (found.size != 1) refuse("expected exactly one method named $method, found ${found.size}")
        return found.single()
    }

    internal fun callPattern(name: String) = Regex("""(?<!\w)${Regex.escape(name)}\s*\(""")

    // ── brackets and commas, on the masked text ──────────────────────────

    /** The index of the bracket that closes the one at [open]. */
    internal fun closing(lexed: Lexed, open: Int): Int {
        val opener = lexed.masked[open]
        val closer = when (opener) {
            '(' -> ')'
            '{' -> '}'
            else -> ']'
        }
        var depth = 0
        for (index in open until lexed.masked.length) {
            when (lexed.masked[index]) {
                opener -> depth++
                closer -> if (--depth == 0) return index
            }
        }
        refuse("the '$opener' at '${lexed.code.substring(open, minOf(open + 60, lexed.code.length))}' is never closed")
    }

    /** The arguments of the call whose opening parenthesis is at [open], split at top-level commas. */
    internal fun argumentsOf(lexed: Lexed, open: Int): List<String> {
        val close = closing(lexed, open)
        val pieces = ArrayList<IntRange>()
        var depth = 0
        var from = open + 1
        for (index in open + 1 until close) {
            when (lexed.masked[index]) {
                '(', '[', '{' -> depth++
                ')', ']', '}' -> depth--
                ',' -> if (depth == 0) {
                    pieces.add(from until index)
                    from = index + 1
                }
            }
        }
        pieces.add(from until close)
        val texts = pieces.map { tidy(lexed, it) }
        return when {
            texts.size == 1 && texts.single().isEmpty() -> emptyList()
            texts.size > 1 && texts.last().isEmpty() -> texts.dropLast(1) // a trailing comma
            else -> texts
        }
    }

    /** The code in [range], trimmed, with runs of whitespace outside string literals made one space. */
    internal fun tidy(lexed: Lexed, range: IntRange): String {
        val out = StringBuilder()
        var spacePending = false
        for (index in range) {
            if (lexed.masked[index].isWhitespace()) {
                spacePending = out.isNotEmpty()
                continue
            }
            if (spacePending) out.append(' ')
            spacePending = false
            out.append(lexed.code[index])
        }
        return out.toString()
    }

    // ── comments and string literals ─────────────────────────────────────

    /**
     * [source] read once: [code] is the text without comments, [masked] is the same text with the text of
     * every string and character literal replaced by [MASK] (same length, same positions), and [literals]
     * are the positions of the literals in [code].
     *
     * The expression of a `${...}` template is not text: it is kept in [masked] as code, so a call written
     * there is found like any other. The `${` and the `}` that delimit it, and any string literal written
     * inside it, are masked. [MASK] is not a word character, so a name in the expression is never taken for
     * part of a longer one because of the text next to it.
     */
    internal class Lexed(val code: String, val masked: String, val literals: List<IntRange>)

    internal fun lex(source: String): Lexed {
        val code = StringBuilder(source.length)
        val masked = StringBuilder(source.length)
        val literals = ArrayList<IntRange>()
        var index = 0
        while (index < source.length) {
            val char = source[index]
            when {
                source.startsWith("//", index) ->
                    index = source.indexOf('\n', index).let { if (it < 0) source.length else it }
                source.startsWith("/*", index) -> {
                    index = blockCommentEnd(source, index)
                    code.append(' ')
                    masked.append(' ')
                }
                char == '"' || char == '\'' -> {
                    val end = literalEnd(source, index)
                    literals.add(code.length until code.length + (end - index))
                    code.append(source, index, end)
                    maskLiteral(source, index, end, masked)
                    index = end
                }
                else -> {
                    code.append(char)
                    masked.append(char)
                    index++
                }
            }
        }
        return Lexed(code.toString(), masked.toString(), literals)
    }

    /**
     * Appends the literal at [start, end) of [source] to [out] with its quotes kept and its text replaced by
     * [MASK], except that the expression of a `${...}` template is kept as code, with its own literals
     * masked the same way.
     */
    private fun maskLiteral(source: String, start: Int, end: Int, out: StringBuilder) {
        val quotes = if (source.startsWith("\"\"\"", start) && end - start >= 6) 3 else 1
        val raw = quotes == 3
        val textEnd = end - quotes
        out.append(source, start, start + quotes)
        var index = start + quotes
        while (index < textEnd) {
            when {
                // A backslash escapes the next character, so `\${` is not a template.
                !raw && source[index] == '\\' -> {
                    val length = minOf(2, textEnd - index)
                    repeat(length) { out.append(MASK) }
                    index += length
                }
                source.startsWith("\${", index) -> {
                    val expressionEnd = templateEnd(source, index + 2)
                    out.append(MASK).append(MASK)
                    maskExpression(source, index + 2, expressionEnd - 1, out)
                    out.append(MASK)
                    index = expressionEnd
                }
                else -> {
                    out.append(MASK)
                    index++
                }
            }
        }
        out.append(source, textEnd, end)
    }

    /** Appends the template expression at [from, to) of [source] to [out]: code, but with its literals masked. */
    private fun maskExpression(source: String, from: Int, to: Int, out: StringBuilder) {
        var index = from
        while (index < to) {
            if (source[index] == '"' || source[index] == '\'') {
                val end = literalEnd(source, index)
                maskLiteral(source, index, end, out)
                index = end
            } else {
                out.append(source[index])
                index++
            }
        }
    }

    /** The index just past the block comment at [start]; Kotlin block comments nest. */
    private fun blockCommentEnd(source: String, start: Int): Int {
        var depth = 0
        var index = start
        while (index < source.length) {
            when {
                source.startsWith("/*", index) -> {
                    depth++
                    index += 2
                }
                source.startsWith("*/", index) -> {
                    depth--
                    index += 2
                    if (depth == 0) return index
                }
                else -> index++
            }
        }
        refuse("the block comment that starts on line ${lineOf(source, start)} never ends")
    }

    /** The index just past the string, raw string or character literal that starts at [start]. */
    private fun literalEnd(source: String, start: Int): Int {
        if (source[start] == '\'') return characterEnd(source, start)
        val raw = source.startsWith("\"\"\"", start)
        var index = start + if (raw) 3 else 1
        while (index < source.length) {
            when {
                raw && source.startsWith("\"\"\"", index) -> {
                    var end = index + 3
                    while (end < source.length && source[end] == '"') end++
                    return end
                }
                !raw && source[index] == '"' -> return index + 1
                // Only a raw string may run over a line; a string that meets one has no end.
                !raw && source[index] == '\n' -> index = source.length
                !raw && source[index] == '\\' -> index += 2
                source.startsWith("\${", index) -> index = templateEnd(source, index + 2)
                else -> index++
            }
        }
        refuse(
            "the ${if (raw) "raw string" else "string"} literal that starts on line ${lineOf(source, start)} " +
                "never ends",
        )
    }

    /** The index just past the character literal at [start]; an escape such as `'\''` is one character. */
    private fun characterEnd(source: String, start: Int): Int {
        var index = start + 1
        while (index < source.length && source[index] != '\n') {
            when (source[index]) {
                '\'' -> return index + 1
                '\\' -> index += 2
                else -> index++
            }
        }
        refuse("the character literal that starts on line ${lineOf(source, start)} never ends")
    }

    /** The line, counting from 1, that holds the character at [index] of [source]. */
    private fun lineOf(source: String, index: Int): Int = 1 + source.take(index).count { it == '\n' }

    /** The index just past the `}` that closes a string template whose contents start at [from]. */
    private fun templateEnd(source: String, from: Int): Int {
        var depth = 1
        var index = from
        while (index < source.length) {
            when (source[index]) {
                '"', '\'' -> {
                    index = literalEnd(source, index)
                    continue
                }
                '{' -> depth++
                '}' -> if (--depth == 0) return index + 1
            }
            index++
        }
        return source.length
    }

    internal fun refuse(message: String): Nothing = throw AdapterShapeError(message)
