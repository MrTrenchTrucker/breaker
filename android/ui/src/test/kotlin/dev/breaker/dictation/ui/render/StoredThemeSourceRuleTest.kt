package dev.breaker.dictation.ui.render

/*
 * The source rule for the theme: every screen entry builds its theme through the shared
 * reader storedTheme, and no entry body writes a system-mode literal or a Themes.of call.
 * The entries are found by scanning the entry files, so a new entry function cannot
 * escape the rule by being absent from a list. The settings entry is the one carve-out,
 * by name: it draws live through the ThemeController that the user switches on its own
 * cells, so its theme source is that controller. It still passes both bans.
 */

import dev.breaker.dictation.ui.gate.MAIN_SOURCES
import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private const val HOME = "HomeEntry.kt"

/**
 * The one entry that draws live through the ThemeController rather than the shared reader.
 * The user switches the theme on its own cells, so the controller is its theme source. It is
 * carved out of the reader line by name, and it must still pass the two bans.
 */
private const val SETTINGS_ENTRY = "createSettingsView"

/** The entry functions the scan must find, each by name, across the entry files. */
private val KNOWN_ENTRIES = listOf(
    "createHomeView",
    "createFirstRunView",
    "createSettingsView",
    "createOnboardingView",
    "createHistoryView",
)

/** A top level entry function declaration: `fun createXxxView(` at the start of a line. */
private val ENTRY_DECLARATION = Regex("""(?m)^fun\s+(create\w+View)\s*\(""")

/** A made-up entry function whose theme line is not the shared reader's, for the sample. */
private val EXTRA_ENTRY = """
fun createExtraView(context: android.content.Context): android.view.View {
    val theme = 1
    return android.view.View(context)
}
"""

/**
 * The entry files: the main sources held directly in the ui package whose names end in
 * Entry.kt, sorted. A file in a sub-folder has a slash in its path and is not one of them.
 */
private fun entryFiles(): List<String> =
    MAIN_SOURCES
        .map { pair -> pair.first }
        .filter { path -> !path.contains('/') && path.endsWith("Entry.kt") }
        .sorted()

/** The names of the top level entry functions in [code], which has comments and strings removed. */
private fun entriesIn(code: String): List<String> =
    ENTRY_DECLARATION.findAll(code).map { match -> match.groupValues[1] }.toList()

/** The code of the main source [file], with its comments and string literals removed. */
private fun codeOf(file: String): String = withoutCommentsAndStrings(mainSourceOf(file))

/**
 * The body of the [fn] entry function in [code], from its opening brace to its own closing brace,
 * where [code] has comments and string literals removed.
 */
private fun bodyOf(code: String, fn: String): String {
    val start = code.indexOf("fun $fn(")
    assertTrue("the $fn entry function is not in the source", start >= 0)
    val open = code.indexOf('{', start)
    assertTrue("the $fn entry function has no body", open >= 0)
    var depth = 0
    for (index in open until code.length) {
        when (code[index]) {
            '{' -> depth++
            '}' -> {
                depth--
                if (depth == 0) return code.substring(open + 1, index)
            }
        }
    }
    throw IllegalArgumentException("the $fn body never closes")
}

/** The text with its first [old] replaced by [new]. It fails by name when [old] is not in the text. */
private fun edit(text: String, old: String, new: String): String {
    assertTrue("edit: the old text is not in the source: $old", text.contains(old))
    return text.replace(old, new)
}

class StoredThemeSourceRuleTest {

    @Test
    fun `the scan finds the five known entries`() {
        assertTrue(
            "entryFiles: the scan found fewer than three entry files, so the rules below would pass on nothing",
            entryFiles().size >= 3,
        )
        val found = entryFiles().flatMap { file -> entriesIn(codeOf(file)) }.toSet()
        for (known in KNOWN_ENTRIES) {
            assertTrue(
                "$known: the scan did not find it in any entry file, so the rules below would not reach it",
                found.contains(known),
            )
        }
    }

    @Test
    fun `no entry body writes a system mode literal or a Themes of call`() {
        for (file in entryFiles()) {
            val code = codeOf(file)
            for (fn in entriesIn(code)) {
                val body = bodyOf(code, fn)
                assertFalse("$fn: a system-mode literal reached its body", body.contains("shownMode(StoredThemeMode.SYSTEM"))
                assertFalse("$fn: a Themes.of call reached its body", body.contains("Themes.of("))
            }
        }
    }

    @Test
    fun `every entry but the settings one builds its theme through the shared reader`() {
        for (file in entryFiles()) {
            val code = codeOf(file)
            for (fn in entriesIn(code)) {
                val body = bodyOf(code, fn)
                if (fn == SETTINGS_ENTRY) {
                    assertTrue("$fn: the settings screen does not draw through the ThemeController", body.contains("ThemeController("))
                } else {
                    assertTrue("$fn: the theme is not the shared reader's line", body.contains("val theme = storedTheme("))
                }
            }
        }
    }

    @Test
    fun `an entry the scan was never told about breaks the rule`() {
        val sample = codeOf(HOME) + EXTRA_ENTRY
        assertTrue("createExtraView: the scan did not find the made-up entry", entriesIn(sample).contains("createExtraView"))
        val extra = bodyOf(sample, "createExtraView")
        assertFalse(
            "createExtraView: its body has the shared reader's line, so the rule would not fire on it",
            extra.contains("val theme = storedTheme("),
        )
        val home = edit(codeOf(HOME), "val theme = storedTheme(", "val theme = Themes.of(")
        assertTrue(
            "createHomeView: the Themes.of call in the sample is not seen by the ban",
            bodyOf(home, "createHomeView").contains("Themes.of("),
        )
    }
}
