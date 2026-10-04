package dev.breaker.dictation.ui.gate

/*
 * How a declaration is read out of a source file, once, for every gate that
 * looks at one.
 *
 * Two questions have to be answered before a scan can say anything about a
 * declaration: where does the declaration end, and what is its text once line
 * breaks and spacing stop mattering. Both are settled here, because a gate that
 * settled them for itself would settle them differently and the set of gates
 * would disagree with itself.
 */

/**
 * A top level declaration, with whatever modifiers precede the keyword.
 *
 * Every part of the prefix is optional. A declaration in Kotlin need carry no
 * visibility marker at all — `fun build()` at the top level is public — and the one
 * declaration this module offers, `createSettingsView`, carries none. Requiring a
 * modifier here would read the module's own public surface as empty, and a gate that
 * sees no declarations passes on an empty module.
 */
internal val TOP_LEVEL_DECLARATION = Regex(
    """^(?:(?:public|internal|private|expect|actual|abstract|final|open|external)\s+)?""" +
        """(?:data\s+|sealed\s+|enum\s+|annotation\s+|value\s+|const\s+)*""" +
        """(?:class|interface|object|fun|val|var|typealias)\b""",
)

/**
 * The declarations of [text] that start in the first column, comments removed.
 *
 * A declaration continues onto the next line while its parameter list is still
 * open, so a signature written across several lines is read whole: taking only
 * the first line of one would compare an opening parenthesis against a settled
 * signature and report a difference that is only a line break. An opening brace
 * does not continue it, because a brace on the end of a signature opens the body
 * and the body is not part of the declaration being offered. The brace is then
 * dropped from the text, so `internal class Holder {` reads as the declaration it
 * is rather than as the declaration and its body.
 */
internal fun topLevelDeclarationsIn(text: String): List<String> {
    val lines = withoutComments(text).lines()
    val declarations = mutableListOf<String>()
    var index = 0
    while (index < lines.size) {
        val line = lines[index]
        if (!TOP_LEVEL_DECLARATION.containsMatchIn(line)) {
            index++
            continue
        }
        val parts = mutableListOf(line.trim())
        while (continues(parts.joinToString("")) && index + 1 < lines.size) {
            index++
            parts.add(lines[index].trim())
        }
        declarations.add(parts.joinToString(" ").removeSuffix("{").trim())
        index++
    }
    return declarations
}

/**
 * Whether a declaration's parameter list is still open at the end of [text].
 *
 * Only parentheses and brackets count. A brace is a body opening, and a body
 * opening on the end of a signature is where the declaration stops: reading past
 * it would swallow the function body into the declaration, and the declaration
 * would then no longer compare equal to the settled signature it is meant to
 * match. A line that closes every bracket ends the signature whether or not a
 * body follows, so both `fun f(): Int = 1` and `fun f(): Int {` end here.
 */
private fun continues(text: String): Boolean {
    var open = 0
    for (character in text) {
        when (character) {
            '(', '[' -> open++
            ')', ']' -> open--
        }
    }
    return open > 0
}

/** The top level declarations of [text] that are not marked internal or private. */
internal fun offeredIn(text: String): List<String> =
    topLevelDeclarationsIn(text).filterNot { declaration ->
        declaration.startsWith("internal ") || declaration.startsWith("private ")
    }

/**
 * The declarations of [text] as a shape, for comparison against a settled one.
 *
 * Line breaks and indentation become single spaces, and a space just inside a
 * bracket pair goes while the bracket itself stays, so a declaration written
 * across four lines is the same shape as one written on one. A space after a comma
 * is kept, because two parameters separated by a comma and nothing else are a
 * different signature from two separated by a comma and a name.
 */
internal fun shaped(text: String): String = text
    .replace(WHITESPACE_RUN, " ")
    .replace(SPACE_BEFORE_BRACKET, "\$1")
    .replace(SPACE_AFTER_BRACKET, "\$1")
    .trim()

/** A run of whitespace, of any length. */
private val WHITESPACE_RUN = Regex("""\s+""")

/** Whitespace just inside a bracket pair, which says nothing about the signature. */
private val SPACE_BEFORE_BRACKET = Regex("""\s+([)\]}])""")

/** Whitespace just after an opening bracket, which says nothing about the signature. */
private val SPACE_AFTER_BRACKET = Regex("""([(\[{])\s+""")
