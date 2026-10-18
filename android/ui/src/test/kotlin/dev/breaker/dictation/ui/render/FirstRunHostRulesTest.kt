package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.shaped
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The first-run host view, pinned as text, because no JVM test can build the view.
 *
 * The host draws the controller's page when it is attached and again when the window
 * gains focus, keeps the scroll position across a redraw, replaces the one child it
 * draws, and sends each tap through the controller before it draws again. Each rule
 * reads the code of the real file, with comments and string literals removed and every
 * run of whitespace made one space. Each rule is shown to fire on edited copies of that
 * file, and each edit is made by a helper that first proves the text it changes is
 * present exactly once, so a sample cannot pass by changing nothing.
 */

private const val HOST_SOURCE = "render/FirstRunHostView.kt"

private val ATTACH_HEADER = Regex("""override\s+fun\s+onAttachedToWindow\(\s*\)\s*\{""")
private val FOCUS_HEADER = Regex("""override\s+fun\s+onWindowFocusChanged\(\s*(\w+)\s*:\s*Boolean\s*\)\s*\{""")
private val DRAW_HEADER = Regex("""private\s+fun\s+draw\(\s*\)\s*\{""")
private val GRANT_HEADER = Regex("""private\s+fun\s+grant\(\s*action\s*:\s*OpenAction\s*\)\s*\{""")
private val NEXT_HEADER = Regex("""private\s+fun\s+next\(\s*\)\s*\{""")
private val DOWNLOAD_TAP_HEADER = Regex("""private\s+fun\s+downloadTap\(\s*\)\s*\{""")
private val HELD_READ = Regex(
    """val\s+held\s*=\s*if\s*\(\s*shown\.page\s*==\s*drawnPage\s*&&\s*shown\.notice\s*==\s*null\s*\)\s*scroller\?\.scrollY\s*\?:\s*0\s+else\s+0""",
)
private val DRAW_CALL = Regex("""\bdraw\(\)""")

/** The code of [text] with comments and literals removed and whitespace flattened. */
private fun flatCode(text: String): String = shaped(withoutCommentsAndStrings(text))

/** The text inside the brace at [open], up to the brace that closes it. */
private fun braceBlock(code: String, open: Int): String {
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
    return code.substring(open + 1)
}

/** The text of the member that [header] starts, or null when [header] is not in [code]. */
private fun blockOf(code: String, header: Regex): String? =
    header.find(code)?.let { braceBlock(code, it.range.last) }

/** [text] with its one occurrence of [old] replaced by [new]; fails unless [old] is present exactly once. */
private fun editOnce(text: String, old: String, new: String): String {
    assertEquals("the text to change is not present exactly once: $old", 1, text.split(old).size - 1)
    return text.replace(old, new)
}

/** Asserts that one of [problems] names [reason], so the edited sample is shown to fire. */
private fun assertFires(what: String, problems: List<String>, reason: String) {
    assertTrue("$what: nothing fired, expected '$reason' in $problems", problems.any { it.contains(reason) })
}

/** Clause one: the host draws the current screen when it is attached, and calls super first. */
private fun attachProblemsIn(text: String): List<String> {
    val body = blockOf(flatCode(text), ATTACH_HEADER) ?: return listOf("onAttachedToWindow is not overridden")
    val superAt = body.indexOf("super.onAttachedToWindow()")
    val drawAt = DRAW_CALL.find(body)?.range?.first ?: -1
    val problems = mutableListOf<String>()
    if (superAt < 0) problems.add("attachment does not call super")
    if (drawAt < 0) problems.add("attachment does not draw the current screen")
    if (superAt >= 0 && drawAt >= 0 && drawAt < superAt) problems.add("attachment calls super after the draw")
    return problems
}

/** Clause two: the host draws again when the window gains focus, and only inside that guard. */
private fun focusProblemsIn(text: String): List<String> {
    val code = flatCode(text)
    val header = FOCUS_HEADER.find(code) ?: return listOf("onWindowFocusChanged is not overridden")
    val body = braceBlock(code, header.range.last)
    val guard = Regex("""if\s*\(\s*""" + header.groupValues[1] + """\s*\)\s*\{""").find(body)
        ?: return listOf("the redraw is not guarded by the window gaining focus")
    val guarded = braceBlock(body, guard.range.last)
    return if (DRAW_CALL.containsMatchIn(guarded)) emptyList() else listOf("gaining focus does not draw the current screen")
}

/** Clause three: the draw reads the scroll position, removes the old child, and puts the position back. */
private fun keepProblemsIn(text: String): List<String> {
    val body = blockOf(flatCode(text), DRAW_HEADER) ?: return listOf("the draw of the host is not found")
    val problems = mutableListOf<String>()
    if (HELD_READ.find(body) == null) problems.add("the scroll position is not read before the redraw")
    if (!body.contains("removeAllViews()")) problems.add("the old child is not replaced")
    if (!body.contains("scroller?.scrollTo(0, held)")) problems.add("the scroll position is not put back")
    return problems
}

/** Clause four: the page wiring hands the three closures over, and each closure reaches the controller and draws. */
private fun tapProblemsIn(text: String): List<String> {
    val code = flatCode(text)
    val problems = mutableListOf<String>()
    val draw = blockOf(code, DRAW_HEADER).orEmpty()
    if (!draw.contains("downloadPage(shown, this::downloadTap)")) problems.add("the download closure is not handed to the download page")
    if (!draw.contains("permissionPage(shown, dark, this::grant, this::next)")) {
        problems.add("the grant and next closures are not handed to the permission page")
    }
    val closures = listOf(
        Triple("grant", GRANT_HEADER, "controller.tap(SetupActions.stringFor(action))"),
        Triple("next", NEXT_HEADER, "controller.advance()"),
        Triple("download", DOWNLOAD_TAP_HEADER, "controller.tapDownload()"),
    )
    for ((name, header, call) in closures) {
        val body = blockOf(code, header)
        if (body == null) {
            problems.add("the $name closure is not found")
        } else {
            if (!body.contains(call)) problems.add("the $name closure does not reach the controller")
            if (!DRAW_CALL.containsMatchIn(body)) problems.add("the $name closure does not draw the page again")
        }
    }
    return problems
}

/** The first-run host view, pinned as text: each clause on the real file, and edited copies that must fail. */
class FirstRunHostRulesTest {
    private val host: String = mainSourceOf(HOST_SOURCE)

    @Test
    fun `the host draws the current screen on attachment, after it calls super`() {
        assertEquals("attachment of the real host", emptyList<String>(), attachProblemsIn(host))
        val calls = "super.onAttachedToWindow()\n        controller.refresh()\n        draw()"
        assertFires(
            "no draw on attachment",
            attachProblemsIn(editOnce(host, calls, "super.onAttachedToWindow()\n        controller.refresh()\n        Unit")),
            "does not draw",
        )
        assertFires("no call to super", attachProblemsIn(editOnce(host, "super.onAttachedToWindow()", "Unit")), "does not call super")
        assertFires(
            "no override of attachment",
            attachProblemsIn(editOnce(host, "override fun onAttachedToWindow()", "fun onAttachedToWindowLater()")),
            "not overridden",
        )
        assertFires(
            "super after the draw",
            attachProblemsIn(editOnce(host, calls, "controller.refresh()\n        draw()\n        super.onAttachedToWindow()")),
            "after the draw",
        )
        assertEquals(
            "a comment after the super call",
            emptyList<String>(),
            attachProblemsIn(editOnce(host, "super.onAttachedToWindow()", "super.onAttachedToWindow()\n        // the page is drawn below")),
        )
    }

    @Test
    fun `the host draws the current screen again only when the window gains focus`() {
        assertEquals("focus of the real host", emptyList<String>(), focusProblemsIn(host))
        val drawInGuard = "controller.refresh()\n            draw()\n        }"
        assertFires(
            "the guard reversed",
            focusProblemsIn(editOnce(host, "if (hasWindowFocus) {", "if (!hasWindowFocus) {")),
            "not guarded",
        )
        assertFires(
            "no override of focus",
            focusProblemsIn(editOnce(host, "override fun onWindowFocusChanged", "fun onWindowFocusChangedLater")),
            "not overridden",
        )
        assertFires(
            "no draw on focus",
            focusProblemsIn(editOnce(host, drawInGuard, "controller.refresh()\n            Unit\n        }")),
            "does not draw",
        )
        assertFires(
            "the draw outside the guard",
            focusProblemsIn(editOnce(host, drawInGuard, "controller.refresh()\n        }\n        draw()")),
            "does not draw",
        )
        assertEquals(
            "a comment after the super call",
            emptyList<String>(),
            focusProblemsIn(editOnce(host, "super.onWindowFocusChanged(hasWindowFocus)", "super.onWindowFocusChanged(hasWindowFocus)\n        // the page is drawn again below")),
        )
    }

    @Test
    fun `the host keeps its scroll position across a redraw and replaces the old child`() {
        assertEquals("the draw of the real host", emptyList<String>(), keepProblemsIn(host))
        assertFires("the old child kept", keepProblemsIn(editOnce(host, "removeAllViews()", "Unit")), "not replaced")
        assertFires(
            "the scroll position not put back",
            keepProblemsIn(editOnce(host, "scroller?.scrollTo(0, held)", "scroller?.scrollTo(0, 0)")),
            "not put back",
        )
        assertFires(
            "the scroll kept only on the same page",
            keepProblemsIn(editOnce(host, "shown.page == drawnPage && shown.notice == null", "shown.page == drawnPage")),
            "not read before the redraw",
        )
        assertEquals(
            "a comment after the removal",
            emptyList<String>(),
            keepProblemsIn(editOnce(host, "removeAllViews()", "removeAllViews()\n        // the old page is replaced below")),
        )
    }

    @Test
    fun `a tap goes through the grant, next and download closures to the controller`() {
        assertEquals("the closures of the real host", emptyList<String>(), tapProblemsIn(host))
        assertFires(
            "the download closure not wired",
            tapProblemsIn(editOnce(host, "downloadPage(shown, this::downloadTap)", "downloadPage(shown, this::next)")),
            "not handed to the download page",
        )
        assertFires(
            "the grant not reaching the controller",
            tapProblemsIn(editOnce(host, "controller.tap(SetupActions.stringFor(action))", "Unit")),
            "grant closure does not reach the controller",
        )
        assertFires(
            "the next not reaching the controller",
            tapProblemsIn(editOnce(host, "controller.advance()", "Unit")),
            "next closure does not reach the controller",
        )
        assertEquals(
            "a comment in the grant",
            emptyList<String>(),
            tapProblemsIn(editOnce(host, "controller.tap(SetupActions.stringFor(action))", "controller.tap(SetupActions.stringFor(action))\n        // the page is drawn again below")),
        )
    }
}
