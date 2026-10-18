package dev.breaker.dictation.ui.render

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The public surface the module's entry file offers, read from its source.
 *
 * Every declaration an app may call is checked here, and only here, so a change
 * to `SettingsEntry.kt` is caught by the test that reads the file rather than by
 * the behaviour tests. This file was split out of `ObserverBindingTest`, which
 * was over the 300-line cap; the binding's own tests stay there.
 */
class EntrySurfaceTest {
    /** The public declaration this module offers an app, written on one line. */
    private val ENTRY_SIGNATURE =
        "fun createSettingsView(context: android.content.Context, " +
            "settings: dev.breaker.dictation.core.port.SettingsStore, " +
            "modelReady: () -> Boolean, downloadLine: () -> String?, " +
            "onDownloadModel: () -> Unit): android.view.View"

    /** The public declaration of the history view, written on one line. */
    private val HISTORY_SIGNATURE =
        "fun createHistoryView(context: android.content.Context, " +
            "history: dev.breaker.dictation.core.port.HistoryStore, " +
            "settings: dev.breaker.dictation.core.port.SettingsStore): android.view.View"

    private val ANY_WHITESPACE = Regex("\\s+")

    /** A comma just before a closing bracket: Kotlin's trailing comma, which says nothing about a signature. */
    private val TRAILING_COMMA = Regex("""\s*,\s*([])}])""")

    /** Whitespace just before a closing bracket, which says nothing about a signature. */
    private val SPACE_BEFORE_BRACKET = Regex("""\s+([)\]}])""")

    /** Whitespace just after an opening bracket, which says nothing about a signature. */
    private val SPACE_AFTER_BRACKET = Regex("""([(\[{])\s+""")

    /**
     * [text] with line breaks and spacing flattened and Kotlin's trailing comma dropped,
     * so a signature written across lines, with a comma on its last parameter, matches a
     * pin written on one line without it.
     */
    private fun flattenedOf(text: String): String =
        text.replace(ANY_WHITESPACE, " ")
            .replace(SPACE_BEFORE_BRACKET, "$1")
            .replace(SPACE_AFTER_BRACKET, "$1")
            .replace(TRAILING_COMMA, "$1")

    /** The words that open a top level declaration, followed by the declaration itself. */
    private val DECLARATION_START =
        Regex("^(fun|val|var|class|object|interface|typealias|annotation class|enum class|sealed class|data class) ")

    /**
     * The top level declarations in [text] that are neither `internal` nor `private`.
     *
     * Comments go first: a line of prose that begins with one of these words is
     * not a declaration, and one that must not be counted must not be able to
     * hide one that should be.
     */
    private fun offeredDeclarations(text: String): List<String> =
        withoutComments(text).lines()
            // A declaration at the top level starts in the first column. A line
            // inside a function body is indented, and trimming first would count
            // every local val in the file as something offered to an app.
            .filter { DECLARATION_START.containsMatchIn(it) }
            .map { it.removeSuffix("{").trimEnd() }
            .filterNot { it.startsWith("internal ") || it.startsWith("private ") }

    /** Comments declare nothing, so a line of prose cannot open a declaration. */
    private fun withoutComments(text: String): String =
        text.lines()
            .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") }
            .joinToString("\n")

    /** The source at [relative] under this module, so the scan follows the module and not one machine. */
    private fun sourceOf(relative: String): String {
        val file = File("src/main/kotlin/dev/breaker/dictation/ui/$relative")
        assertTrue("the source was not found at ${file.absolutePath}", file.isFile)
        return file.readText()
    }

    /** Four declarations offered to an app, where the entry file may offer four. */
    private val CONTROL_WITH_FOUR_OFFERED =
        """
        package dev.breaker.dictation.ui

        fun createSettingsView(context: android.content.Context, settings: dev.breaker.dictation.core.port.SettingsStore): android.view.View {
            return View(context)
        }

        fun createOtherView(context: android.content.Context): android.view.View {
            return View(context)
        }

        fun createHowItWorksView(context: android.content.Context): android.view.View {
            return View(context)
        }

        fun createAboutView(context: android.content.Context): android.view.View {
            return View(context)
        }
        """.trimIndent()

    /** A settings view that takes any object, which is not the declaration this module offers. */
    private val CONTROL_WEAK_PARAMETER =
        "fun createSettingsView(context: android.content.Context, settings: Any): android.view.View"

    /** A history view that takes any object for its store, which is not the declaration this module offers. */
    private val CONTROL_WEAK_HISTORY =
        "fun createHistoryView(context: android.content.Context, history: Any): android.view.View"

    @Test
    fun `the entry file offers four declarations of settings view, history view, how it works view and about view`() {
        val offered = offeredDeclarations(sourceOf("SettingsEntry.kt"))
        assertEquals("exactly four declarations may be offered by the entry file", 4, offered.size)
        assertTrue("the first offered declaration must be the settings view", offered.any { it.startsWith("fun createSettingsView(") })
        assertTrue("the second offered declaration must be the history view", offered.any { it.startsWith("fun createHistoryView(") })
        assertTrue("the third offered declaration must be the how it works view", offered.any { it.startsWith("fun createHowItWorksView(") })
        assertTrue("the fourth offered declaration must be the about view", offered.any { it.startsWith("fun createAboutView(") })

        val control = offeredDeclarations(CONTROL_WITH_FOUR_OFFERED)
        assertEquals("the scan must catch a fourth offered declaration", 4, control.size)
    }

    @Test
    fun `the settings view takes a context and a settings store and gives back a view`() {
        val flattened = flattenedOf(sourceOf("SettingsEntry.kt"))
        assertTrue(
            "the pinned declaration must be there with both parameter types",
            flattened.contains(ENTRY_SIGNATURE),
        )
        assertFalse(
            "the scan must not accept a parameter type that has been weakened",
            CONTROL_WEAK_PARAMETER.contains(ENTRY_SIGNATURE),
        )
    }

    @Test
    fun `the history view takes a context and a history store and gives back a view`() {
        val flattened = flattenedOf(sourceOf("SettingsEntry.kt"))
        assertTrue(
            "the pinned declaration must be there with both parameter types",
            flattened.contains(HISTORY_SIGNATURE),
        )
        assertFalse(
            "the scan must not accept a history parameter that has been weakened",
            CONTROL_WEAK_HISTORY.contains(HISTORY_SIGNATURE),
        )
    }
}
