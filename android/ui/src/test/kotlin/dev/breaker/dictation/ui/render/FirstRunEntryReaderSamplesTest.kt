package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The reader that finds the first-run entry function. It must find the real function,
 * and it must not find a function name that appears only in a comment or only inside a
 * string literal. The reading is the same text as the reader in the wiring test, which
 * keeps its own copy private.
 */

private const val ENTRY_SOURCE = "FirstRunEntry.kt"

/** The signature and the body of a function, with comments and strings removed. */
private class EntryParts(val signature: String, val body: String)

private fun entryOf(text: String): EntryParts? {
    val code = withoutCommentsAndStrings(text)
    val start = Regex("""\bfun\s+createFirstRunView\s*\(""").find(code) ?: return null
    val open = code.indexOf('{', start.range.last)
    if (open < 0) return null
    var depth = 0
    for (index in open until code.length) {
        when (code[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return EntryParts(code.substring(start.range.first, open), code.substring(open + 1, index))
            }
        }
    }
    return null
}

class FirstRunEntryReaderSamplesTest {
    private val entry: String = mainSourceOf(ENTRY_SOURCE)

    /** A failure means the reader does not find the real first-run entry function, or reads another function. */
    @Test
    fun `the reader finds the real first-run entry function and its body`() {
        val found = requireNotNull(entryOf(entry)) { "the reader did not find createFirstRunView in FirstRunEntry.kt" }
        assertTrue("the signature read is not the first-run entry's", found.signature.contains("createFirstRunView("))
        assertTrue("the body read is not the one that builds the platform", found.body.contains("AndroidSetupPlatform("))
    }

    /** A failure means a function name written only in a block comment is read as a function. */
    @Test
    fun `a first-run entry name that appears only in a comment is not found`() {
        assertNull(
            "a name written only in a comment was found",
            entryOf("/* fun createFirstRunView(context: Context) { } */\n"),
        )
    }

    /** A failure means a function name written only inside a string literal is read as a function. */
    @Test
    fun `a first-run entry name that appears only in a string is not found`() {
        assertNull(
            "a name written only in a string was found",
            entryOf("val x = \"fun createFirstRunView(context: Context) { }\"\n"),
        )
    }
}
