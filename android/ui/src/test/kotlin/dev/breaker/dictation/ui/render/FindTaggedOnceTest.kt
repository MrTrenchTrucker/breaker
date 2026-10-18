package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.MAIN_SOURCES
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The top level lookup of a tagged view is written once, in the renderer's file, and no file under
 * render/ declares a second one. Every file is read as text, and a declaration that starts a line, in
 * any visibility, is a top level lookup. A lookup written as a member of a class is indented and is not
 * counted: the home host keeps one as a member, which is an accepted leftover. Each shape the rule
 * forbids is shown to fire on an edited sample.
 */
class FindTaggedOnceTest {
    private val renderFiles = MAIN_SOURCES.filter { (path, _) -> path.startsWith("render/") }

    private val rendererPath = "render/ScreenRenderer.kt"
    private val historyHost = "render/HistoryHostView.kt"
    private val brandArt = "render/BrandArtViews.kt"
    private val homeHost = "render/HomeHostView.kt"
    private val settingsHost = "render/SettingsHostView.kt"

    /** A top level declaration of the lookup: at the start of a line, with an optional visibility. */
    private val topLevel = Regex("""(?m)^(?:private |internal |public )?fun findTagged\(""")

    /** The one declaration the renderer must hold: internal and top level, so the other files call it. */
    private val shared = "\ninternal fun findTagged(root: View, id: String): View? {"

    /** The number of top level declarations of the lookup in [text], with comments removed first. */
    private fun topLevelIn(text: String): Int = topLevel.findAll(withoutComments(text)).count()

    /** The problems with the one-lookup rule in [files]; an empty list means it holds. */
    private fun problemsIn(files: List<Pair<String, String>>): List<String> {
        val declaredIn = files.flatMap { (path, text) -> List(topLevelIn(text)) { path } }
        return listOfNotNull(
            "the lookup is declared at the top level ${declaredIn.size} times, not exactly once: $declaredIn".takeIf { declaredIn.size != 1 },
            "the renderer does not declare the lookup as internal top level".takeUnless {
                files.any { (path, text) -> path == rendererPath && text.contains(shared) }
            },
            "a second top level lookup is declared in another file: $declaredIn".takeIf { declaredIn.any { it != rendererPath } },
        )
    }

    /** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
    private fun editOnce(text: String, old: String, new: String): String {
        assertEquals(
            "render: find tagged: the text to change is not in the file exactly once: $old",
            1,
            Regex(Regex.escape(old)).findAll(text).count(),
        )
        return text.replace(old, new)
    }

    /** The render sources with [change] applied to the one file [path]. */
    private fun edited(path: String, change: (String) -> String): List<Pair<String, String>> =
        renderFiles.map { (name, text) -> if (name == path) name to change(text) else name to text }

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("render: find tagged: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    @Test
    fun `the top level lookup is declared once, in the renderer, and no render file declares another`() {
        assertTrue("render: find tagged: the render sources were not read", renderFiles.any { (path, _) -> path == rendererPath })
        assertEquals("render: find tagged: the real tree breaks the rule", emptyList<String>(), problemsIn(renderFiles))
        assertFires(
            "a second top level declaration in the renderer",
            problemsIn(edited(rendererPath) { it + "\nprivate fun findTagged(root: View, id: String): View? = null\n" }),
            "exactly once",
        )
        assertFires(
            "a private top level lookup in the renderer",
            problemsIn(edited(rendererPath) { editOnce(it, shared, "\nprivate fun findTagged(root: View, id: String): View? {") }),
            "internal top level",
        )
        assertFires(
            "a top level copy in the history host",
            problemsIn(edited(historyHost) { it + "\nprivate fun findTagged(root: View, id: String): View? = null\n" }),
            historyHost,
        )
        assertFires(
            "a top level copy in the brand art",
            problemsIn(edited(brandArt) { it + "\nfun findTagged(root: View, id: String): View? = null\n" }),
            brandArt,
        )
        assertEquals(
            "render: find tagged: a member lookup in the home host is the accepted leftover and is not counted",
            emptyList<String>(),
            problemsIn(edited(homeHost) { it + "\n    private fun findTagged(root: View, id: String): View? = null\n" }),
        )
        assertEquals(
            "render: find tagged: a declaration in a comment is not a declaration",
            emptyList<String>(),
            problemsIn(edited(settingsHost) { it + "\n// fun findTagged(root: View, id: String): View? = null\n" }),
        )
    }
}
