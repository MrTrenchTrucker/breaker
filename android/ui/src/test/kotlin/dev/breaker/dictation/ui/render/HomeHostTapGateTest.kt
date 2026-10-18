package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import dev.breaker.dictation.ui.gate.withoutCommentsAndStrings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/*
 * The big mic on the Home view takes one tap, and the tap reaches the app once.
 *
 * The view attaches the tap to the one view that carries the mic's id, and the MIC
 * action calls the app's onTap exactly once. Each rule reads the source of the real
 * view with comments and literal text removed, and the one raw text rule reads the
 * source as it is. Every rule is shown to fire on an edited copy of that source before
 * it is held on the real one, and every edit fails by name when the text it changes is
 * not there exactly once, so an edited copy cannot pass as a no-op.
 */

private const val REAL_FILE = "render/HomeHostView.kt"

private val WHITESPACE_RUNS = Regex("""\s+""")

/** The code of [text] with every run of whitespace removed, so line breaks and spacing do not count. */
private fun flatCode(text: String): String = withoutCommentsAndStrings(text).replace(WHITESPACE_RUNS, "")

/** The code of [text] with every run of whitespace made one space, for a shape that keeps its words apart. */
private fun spacedCode(text: String): String = withoutCommentsAndStrings(text).replace(WHITESPACE_RUNS, " ")

/** How many times [token] occurs in [text]. */
private fun occurrenceCount(text: String, token: String): Int =
    Regex(Regex.escape(token)).findAll(text).count()

/** The arguments of the HomeState call that merged() makes, from [code], or an empty string when there is none. */
private fun mergedArguments(code: String): String {
    val header = Regex("""\bfun\s+merged\s*\(""").find(code) ?: return ""
    val rest = code.substring(header.range.last + 1)
    val next = Regex("""\b(?:fun|val|var|init)\s""").find(rest)
    val tail = if (next == null) rest else rest.substring(0, next.range.first)
    val call = Regex("""\bHomeState\s*\(""").find(tail) ?: return ""
    var depth = 1
    for (index in call.range.last + 1 until tail.length) {
        if (tail[index] == '(') depth++
        if (tail[index] == ')') {
            depth--
            if (depth == 0) return tail.substring(call.range.last + 1, index)
        }
    }
    return ""
}

/** The top level parts of [arguments], each with its whitespace removed. */
private fun topLevelParts(arguments: String): List<String> {
    val parts = mutableListOf<String>()
    val current = StringBuilder()
    var depth = 0
    for (ch in arguments) {
        if (ch == '(' || ch == '{' || ch == '[') depth++
        if (ch == ')' || ch == '}' || ch == ']') depth--
        if (ch == ',' && depth == 0) {
            parts.add(current.toString().replace(WHITESPACE_RUNS, ""))
            current.setLength(0)
        } else {
            current.append(ch)
        }
    }
    parts.add(current.toString().replace(WHITESPACE_RUNS, ""))
    return parts
}

/** The named problems with the tap in [text]; an empty list means the view meets every rule. */
private fun tapProblemsIn(text: String): List<String> {
    val flat = flatCode(text)
    val spaced = spacedCode(text)
    val raw = withoutComments(text).replace(WHITESPACE_RUNS, "")
    val problems = mutableListOf<String>()
    if (!flat.contains("):FrameLayout(context),HomeControl{")) problems += "HomeHostView does not implement HomeControl"
    if (!flat.contains("findTagged(rendered,HomeNodeIds.MIC)?.let{attachMic(it)}")) {
        problems += "the mic is not found by its tag"
    }
    if (!flat.contains("mic.setOnClickListener{onIntent(ScreenIntent.Home(HomeActions.MIC))}")) {
        problems += "the click is not on the mic view"
    }
    if (occurrenceCount(flat, "setOnClickListener") != 1) problems += "a click is attached to another view"
    if (!flat.contains("mic.isClickable=true") || !flat.contains("mic.isFocusable=true")) {
        problems += "the mic is not clickable and focusable"
    }
    if (occurrenceCount(flat, "contentDescription") != 1 || !flat.contains("mic.contentDescription=HomeTexts.TAP_TO_TALK")) {
        problems += "the mic has no contentDescription of TAP_TO_TALK"
    }
    if (!raw.contains("info.className=\"android.widget.Button\"")) problems += "the mic is not announced as a button"
    if (occurrenceCount(flat, "onTap()") != 1) problems += "onTap is not called exactly once"
    if (!flat.contains("HomeActions.MIC->if(state.menuOpen)onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU))elseonTap()")) problems += "the MIC action does not call onTap"
    if (!spaced.contains("override fun showLine(line: String?) { this.line = line draw(merged(drawn)) }")) {
        problems += "showLine does not store the line and draw again"
    }
    if (!spaced.contains("override fun showRecording(recording: Boolean) { this.recording = recording draw(merged(drawn)) }")) {
        problems += "showRecording does not store the look and draw again"
    }
    if (!flat.contains("mic.minimumHeight=touchTargetPx(")) problems += "the mic has no minimum touch height"
    if (occurrenceCount(flat, "attachMic(") != 2 || !flat.contains("let{attachMic(it)}")) {
        problems += "the mic is attached to another view"
    }
    if (occurrenceCount(flat, "act(intent.action)") != 1 || !flat.contains("if(intentisScreenIntent.Home)act(intent.action)")) {
        problems += "the Home intent is not acted on exactly once"
    }
    if (!flat.contains("if(root.tag==id)returnroot")) problems += "the tag lookup does not match the id"
    val arguments = topLevelParts(mergedArguments(withoutCommentsAndStrings(text)))
    if ("line=line" !in arguments || "recording=recording" !in arguments) {
        problems += "merged does not pass line and recording"
    }
    if (!flat.contains("valhandled=handler.handle(intent)draw(merged(handled))")) problems += "the handled intent is not drawn again"
    if (!flat.contains("mic.setAccessibilityDelegate(object:View.AccessibilityDelegate(){")) {
        problems += "the mic has no accessibility delegate"
    }
    if (!flat.contains("drawn=screenshow(screen)}")) problems += "draw does not show the screen it keeps"
    if (withoutCommentsAndStrings(text).lines().none { it.trim().startsWith("mic.setAccessibilityDelegate(") }) problems += "the accessibility delegate is not a statement of its own"
    if (!flat.contains("for(indexin0untilgroup.childCount){valfound=findTagged(group.getChildAt(index),id)")) problems += "the tag lookup does not search every child"
    if (!flat.contains("touchTargetPx(theme.metrics.minTouchTargetDp.toFloat(),resources.displayMetrics.density)")) problems += "the mic touch floor is not read from the token"
    return problems
}

/** [text] with [old] replaced by [new]; fails by name when [old] is not exactly once in [text]. */
private fun replaceOnce(text: String, old: String, new: String): String {
    assertTrue("ui: home tap: the text to change is not in the source exactly once: $old", occurrenceCount(text, old) == 1)
    return text.replace(old, new)
}

private fun assertFires(what: String, problems: List<String>, part: String) =
    assertTrue("ui: home tap: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

private fun assertClean(what: String, edited: String) =
    assertEquals("ui: home tap: $what must not be reported", emptyList<String>(), tapProblemsIn(edited))

/** The Home view's tap, pinned as text. */
class HomeHostTapGateTest {
    private val real = mainSourceOf(REAL_FILE)

    @Test
    fun `the real Home view meets every tap rule`() {
        assertEquals("ui: home tap: the real view: ", emptyList<String>(), tapProblemsIn(real))
    }

    @Test
    fun `a view that does not implement HomeControl is reported`() {
        val edited = replaceOnce(real, ") : FrameLayout(context), HomeControl {", ") : FrameLayout(context) {")
        assertFires("the missing interface", tapProblemsIn(edited), "does not implement HomeControl")
        assertClean(
            "the header split over lines",
            replaceOnce(real, ") : FrameLayout(context), HomeControl {", ")\n    : FrameLayout(context),\n    HomeControl {"),
        )
    }

    @Test
    fun `a click that does not follow the mic tag is reported`() {
        val edited = replaceOnce(real, "findTagged(rendered, HomeNodeIds.MIC)", "findTagged(rendered, HomeNodeIds.MENU)")
        assertFires("the other id", tapProblemsIn(edited), "not found by its tag")
        assertClean(
            "the tag lookup split over lines",
            replaceOnce(real, "findTagged(rendered, HomeNodeIds.MIC)", "findTagged(\n            rendered,\n            HomeNodeIds.MIC\n        )"),
        )
    }

    @Test
    fun `a click on a second view or on another node is reported`() {
        val second = replaceOnce(real, "        content.scrollTo(0, held)\n", "        content.scrollTo(0, held)\n        rendered.setOnClickListener { }\n")
        assertFires("a second click", tapProblemsIn(second), "attached to another view")
        val moved = replaceOnce(real, "mic.setOnClickListener {", "rendered.setOnClickListener {")
        assertFires("the click on the row", tapProblemsIn(moved), "not on the mic view")
        assertFires(
            "a second attach",
            tapProblemsIn(replaceOnce(real, "        content.scrollTo(0, held)\n", "        content.scrollTo(0, held)\n        attachMic(rendered)\n")),
            "attached to another view",
        )
        assertClean(
            "the click split over lines",
            replaceOnce(
                real,
                "mic.setOnClickListener { onIntent(ScreenIntent.Home(HomeActions.MIC)) }",
                "mic.setOnClickListener {\n            onIntent(ScreenIntent.Home(HomeActions.MIC))\n        }",
            ),
        )
    }

    @Test
    fun `a mic that is not clickable and focusable is reported`() {
        assertFires("no focus", tapProblemsIn(replaceOnce(real, "mic.isFocusable = true", "")), "not clickable and focusable")
        assertFires("no click", tapProblemsIn(replaceOnce(real, "mic.isClickable = true", "")), "not clickable and focusable")
        assertClean("the flag on its own line", replaceOnce(real, "mic.isClickable = true", "mic.isClickable =\n            true"))
    }

    @Test
    fun `a mic without its tap to talk words as its description is reported`() {
        val dropped = replaceOnce(real, "mic.contentDescription = HomeTexts.TAP_TO_TALK", "")
        assertFires("no description", tapProblemsIn(dropped), "no contentDescription")
        val other = replaceOnce(real, "mic.contentDescription = HomeTexts.TAP_TO_TALK", "mic.contentDescription = HomeTexts.LAST_TRANSCRIPT")
        assertFires("another text", tapProblemsIn(other), "no contentDescription")
        assertClean(
            "the description split over lines",
            replaceOnce(real, "mic.contentDescription = HomeTexts.TAP_TO_TALK", "mic.contentDescription =\n            HomeTexts.TAP_TO_TALK"),
        )
    }

    @Test
    fun `a mic not announced as a button is reported`() {
        val dropped = replaceOnce(real, "info.className = \"android.widget.Button\"", "")
        assertFires("no button class", tapProblemsIn(dropped), "not announced as a button")
        val commented = replaceOnce(real, "info.className = \"android.widget.Button\"", "// info.className = \"android.widget.Button\"")
        assertFires("a comment only", tapProblemsIn(commented), "not announced as a button")
        assertClean(
            "the class name split over lines",
            replaceOnce(real, "info.className = \"android.widget.Button\"", "info.className =\n                    \"android.widget.Button\""),
        )
    }

    @Test
    fun `a MIC action that calls onTap other than once is reported`() {
        val twice = replaceOnce(real, "HomeActions.MIC -> if (state.menuOpen) onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU)) else onTap()", "HomeActions.MIC -> if (state.menuOpen) onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU)) else { onTap(); onTap() }")
        assertFires("two calls", tapProblemsIn(twice), "exactly once")
        val none = replaceOnce(real, "HomeActions.MIC -> if (state.menuOpen) onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU)) else onTap()", "HomeActions.MIC -> if (state.menuOpen) onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU)) else Unit")
        assertFires("no call", tapProblemsIn(none), "exactly once")
        assertFires("no call on the branch", tapProblemsIn(none), "does not call onTap")
        assertClean("the branch split over lines", replaceOnce(real, "HomeActions.MIC -> if (state.menuOpen) onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU)) else onTap()", "HomeActions.MIC -> if (state.menuOpen) onIntent(ScreenIntent.Home(HomeActions.CLOSE_MENU)) else\n                onTap()"))
    }

    @Test
    fun `a showLine or showRecording that does not draw again is reported`() {
        val noDrawLine = replaceOnce(real, "        this.line = line\n        draw(merged(drawn))\n", "        this.line = line\n")
        assertFires("showLine without a draw", tapProblemsIn(noDrawLine), "showLine")
        val noDrawLook = replaceOnce(real, "        this.recording = recording\n        draw(merged(drawn))\n", "        this.recording = recording\n")
        assertFires("showRecording without a draw", tapProblemsIn(noDrawLook), "showRecording")
        assertClean("showLine split over lines", replaceOnce(real, "        this.line = line\n", "        this.line =\n            line\n\n"))
        assertClean("showRecording with a blank line", replaceOnce(real, "        this.recording = recording\n", "        this.recording = recording\n\n"))
    }

    @Test
    fun `a merged that drops the line or the recording look is reported`() {
        val noLine = replaceOnce(real, "        line = line,\n", "")
        assertFires("no line", tapProblemsIn(noLine), "merged does not pass line and recording")
        val noRecording = replaceOnce(real, "        recording = recording,\n", "")
        assertFires("no recording", tapProblemsIn(noRecording), "merged does not pass line and recording")
        assertClean("the line argument split over lines", replaceOnce(real, "        line = line,\n", "        line =\n            line,\n"))
    }

    @Test
    fun `a mic without a touch floor is reported`() {
        val removed = replaceOnce(real, "mic.minimumHeight = touchTargetPx(theme.metrics.minTouchTargetDp.toFloat(), resources.displayMetrics.density)", "")
        assertFires("no floor", tapProblemsIn(removed), "no minimum touch height")
        val otherHelper = replaceOnce(real, "mic.minimumHeight = touchTargetPx(", "mic.minimumHeight = touchSize(")
        assertFires("another helper", tapProblemsIn(otherHelper), "no minimum touch height")
        assertFires("a floor of zero", tapProblemsIn(replaceOnce(real, "touchTargetPx(theme.metrics.minTouchTargetDp.toFloat(), resources", "touchTargetPx(0f, resources")), "not read from the token")
        assertClean("the floor split over lines", replaceOnce(real, "mic.minimumHeight = touchTargetPx(", "mic.minimumHeight =\n            touchTargetPx("))
    }

    @Test
    fun `a Home intent that is acted on twice or not at all is reported`() {
        val guard = "if (intent is ScreenIntent.Home) act(intent.action)"
        assertFires("a guard turned off", tapProblemsIn(replaceOnce(real, guard, "if (false) act(intent.action)")), "not acted on exactly once")
        assertFires("a second act", tapProblemsIn(replaceOnce(real, guard, guard + "\n        " + guard)), "not acted on exactly once")
        assertClean("the guard split over lines", replaceOnce(real, guard, "if (intent is ScreenIntent.Home)\n            act(intent.action)"))
    }

    @Test
    fun `a tag lookup that matches nothing is reported`() {
        val edited = replaceOnce(real, "if (root.tag == id) return root", "if (root.tag == id) return null")
        assertFires("a lookup that returns nothing", tapProblemsIn(edited), "does not match the id")
        assertFires("a lookup of the first child only", tapProblemsIn(replaceOnce(real, "0 until group.childCount", "0 until minOf(1, group.childCount)")), "does not search every child")
        assertClean("the tag test split over lines", replaceOnce(real, "if (root.tag == id) return root", "if (root.tag == id)\n            return root"))
    }

    @Test
    fun `a handled intent that is not drawn again is reported`() {
        assertFires("no draw after the intent", tapProblemsIn(replaceOnce(real, "        draw(merged(handled))\n", "")), "not drawn again")
    }

    @Test
    fun `a mic without its accessibility delegate is reported`() {
        val edited = replaceOnce(real, "mic.setAccessibilityDelegate(", "mic.setOnHoverListener(")
        assertFires("no delegate", tapProblemsIn(edited), "no accessibility delegate")
    }

    @Test
    fun `a draw that does not show the screen it keeps is reported`() {
        val edited = replaceOnce(real, "        drawn = screen\n        show(screen)\n", "        drawn = screen\n")
        assertFires("draw without show", tapProblemsIn(edited), "does not show the screen it keeps")
    }

    @Test
    fun `an accessibility delegate under a false condition is reported`() {
        val edited = replaceOnce(real, "mic.setAccessibilityDelegate(object", "if (false) mic.setAccessibilityDelegate(object")
        assertFires("a switched-off delegate", tapProblemsIn(edited), "not a statement of its own")
    }
}
