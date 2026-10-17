package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source text checks for the inset probe in the window adapter, which never runs on a plain JVM.
 * Each rule runs first on a made-up sample that breaks it, which must be reported, and on one that
 * obeys it, which must stay quiet; only then on the real main sources. Comments are blanked and
 * string literals are kept, as in the other source checks.
 */
class ImeProbeSourceGateTest {

    private val window = "WindowManagerTileWindow.kt"
    private val attachStart = Regex("""setOnApplyWindowInsetsListener\s*\{""")
    private val attachCall = Regex("""\battachImeProbe\s*\(""")
    private val attachDeclaration = Regex("""\bfun\s+attachImeProbe\s*\(""")
    private val whitespace = Regex("""\s+""")
    private val addFlags = "WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** The window source of the real main folder, or a failure naming the file when it is not there. */
    private fun realWindow(): String =
        ModuleFiles.mainTexts()[window] ?: throw AssertionError("overlay: $window is missing from the main sources")

    /** The number of inset listeners attached by each file of [files] that attaches any, by file name. */
    private fun attachCounts(files: Map<String, String>): Map<String, Int> =
        files.mapValues { attachStart.findAll(SourceText.code(it.value)).count() }.filterValues { it > 0 }

    /** A failure here means the inset listener is attached in more than one place, or in a file other than the window. */
    private fun onePlaceProblems(files: Map<String, String>): List<String> {
        val counts = attachCounts(files)
        return if (counts == mapOf(window to 1)) emptyList() else listOf("the inset listener is attached $counts, expected once in $window")
    }

    /** The listener must end by returning the view's own answer, so the view keeps its insets handling. */
    private fun returnProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val start = attachStart.find(code) ?: return listOf("no inset listener is attached")
        val open = start.range.last
        val close = SourceText.closeOf(code, open)
        if (close < 0) return listOf("the inset listener never closes")
        val last = code.substring(open + 1, close).lines().map { it.trim() }.filter { it.isNotEmpty() }.lastOrNull()
        return listOfNotNull("the inset listener does not end by returning v.onApplyWindowInsets(insets)".takeIf { last != "v.onApplyWindowInsets(insets)" })
    }

    /** A failure here means attachImeProbe is called with no null check on the listener, on its line or the line above. */
    private fun unguardedProblems(source: String): List<String> {
        val lines = SourceText.code(source).lines()
        return lines.indices.filter { index ->
            val line = lines[index]
            attachCall.containsMatchIn(line) && !attachDeclaration.containsMatchIn(line) &&
                !line.contains("!= null") && !(index > 0 && lines[index - 1].contains("!= null"))
        }.map { "line ${it + 1}: attachImeProbe is called with no null check on the listener" }
    }

    /** A failure here means a null listener does not detach the view listener, so the probe would stay on after it is turned off. */
    private fun detachProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "setImeProbe") ?: return listOf("no setImeProbe function with a block body was found")
        val detach = Regex("""else\s*\{[^}]*setOnApplyWindowInsetsListener\(null\)""")
        return listOfNotNull("setImeProbe does not detach the view listener in its null branch".takeIf { !detach.containsMatchIn(body) })
    }

    /** A failure here means the inset callback calls the listener before the filter has accepted the pair. */
    private fun orderProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val start = attachStart.find(code) ?: return listOf("no inset listener is attached")
        val open = start.range.last
        val close = SourceText.closeOf(code, open)
        if (close < 0) return listOf("the inset listener never closes")
        val body = code.substring(open + 1, close)
        val accept = body.indexOf("imeChanges.accept(")
        val call = body.indexOf("listener(bottom, visible)")
        return listOfNotNull("the inset callback does not ask the filter before it calls the listener".takeIf { accept < 0 || call < 0 || accept > call })
    }

    /** A failure here means a main file names the keyboard signal setting, which belongs to the debug probe only. */
    private fun settingProblems(files: Map<String, String>): List<String> =
        files.filterValues { it.contains("KeyboardSignal") }.keys.map { "$it names the keyboard signal setting" }

    /** A failure here means add() no longer builds the window with the plain tile's flags. */
    private fun flagProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "add") ?: return listOf("no add function with a block body was found")
        return listOfNotNull("add() does not build the window with the plain tile's flags".takeIf { !whitespace.replace(body, " ").contains(addFlags) })
    }

    private val attachedOnce = "view.setOnApplyWindowInsetsListener { v, insets ->\n val bottom = 0\n v.onApplyWindowInsets(insets)\n}\n"

    /** A failure here means the inset listener is set in two places, or without handing the view its own answer. */
    @Test
    fun `the inset listener is attached in one place only`() {
        assertEquals("overlay: control: an attach in the window must count once", mapOf(window to 1), attachCounts(mapOf(window to attachedOnce)))
        assertFires("two attaches in the window", onePlaceProblems(mapOf(window to attachedOnce + attachedOnce)), "expected once in")
        assertFires("an attach in another file", onePlaceProblems(mapOf(window to attachedOnce, "TileView.kt" to attachedOnce)), "expected once in")
        assertFires("only a detach", onePlaceProblems(mapOf(window to "view.setOnApplyWindowInsetsListener(null)\n")), "expected once in")
        assertQuiet("one attach in the window", onePlaceProblems(mapOf(window to attachedOnce, "TileView.kt" to "val x = 1\n")))

        assertFires("a listener that returns its insets", returnProblems("view.setOnApplyWindowInsetsListener { v, insets ->\n v.onApplyWindowInsets(insets)\n insets\n}\n"), "does not end by returning")
        assertFires("a listener that never calls the view", returnProblems("view.setOnApplyWindowInsetsListener { v, insets ->\n insets\n}\n"), "does not end by returning")
        assertQuiet("a listener that returns the view's answer", returnProblems(attachedOnce))

        assertEquals("overlay: $window must attach the inset listener in one place only", emptyList<String>(), onePlaceProblems(ModuleFiles.mainTexts()))
        assertEquals("overlay: $window listener must end by returning v.onApplyWindowInsets(insets)", emptyList<String>(), returnProblems(realWindow()))

        assertFires("the listener called before the filter", orderProblems("view.setOnApplyWindowInsetsListener { v, insets ->\n listener(bottom, visible)\n if (imeChanges.accept(bottom, visible)) {\n }\n v.onApplyWindowInsets(insets)\n}\n"), "does not ask the filter before it calls the listener")
        assertFires("the listener called with no filter", orderProblems("view.setOnApplyWindowInsetsListener { v, insets ->\n listener(bottom, visible)\n v.onApplyWindowInsets(insets)\n}\n"), "does not ask the filter before it calls the listener")
        assertQuiet("the filter asked before the listener", orderProblems("view.setOnApplyWindowInsetsListener { v, insets ->\n val bottom = 0\n if (imeChanges.accept(bottom, visible)) {\n listener(bottom, visible)\n }\n v.onApplyWindowInsets(insets)\n}\n"))
        assertEquals("overlay: $window must ask the filter before it calls the listener", emptyList<String>(), orderProblems(realWindow()))
    }

    /** A failure here means attachImeProbe is called on a path where the listener may be null. */
    @Test
    fun `the attach is reached only with a listener`() {
        assertFires("an attach in add with no check", unguardedProblems("override fun add() {\n val probe = listener\n attachImeProbe(view, probe)\n}\n"), "no null check")
        assertFires("a check only in a comment", unguardedProblems("// if (probe != null)\nattachImeProbe(view, probe)\n"), "no null check")
        assertQuiet("an attach after a check on its line", unguardedProblems("if (probe != null) attachImeProbe(view, probe)\n"))
        assertQuiet("an attach inside a check on the line above", unguardedProblems("if (listener != null) {\n tileView?.let { attachImeProbe(it, listener) }\n}\n"))

        val real = realWindow()
        val calls = SourceText.code(real).lines().count { attachCall.containsMatchIn(it) && !attachDeclaration.containsMatchIn(it) }
        assertEquals("overlay: $window must call attachImeProbe in the add and in setImeProbe", 2, calls)
        assertEquals("overlay: $window must not call attachImeProbe without a listener check", emptyList<String>(), unguardedProblems(real))

        val nullBranch = "override fun setImeProbe(listener: ((Int, Boolean) -> Unit)?) {\n if (listener != null) {\n tileView?.let { attachImeProbe(it, listener) }\n } else {\n imeChanges.forget()\n }\n}\n"
        assertFires("a null branch that does not detach", detachProblems(nullBranch), "does not detach the view listener")
        assertFires("the detach only in a comment", detachProblems(nullBranch.replace("imeChanges.forget()", "// tileView?.setOnApplyWindowInsetsListener(null)\n imeChanges.forget()")), "does not detach the view listener")
        assertFires("the detach in the attach branch", detachProblems(nullBranch.replace("tileView?.let { attachImeProbe(it, listener) }", "tileView?.setOnApplyWindowInsetsListener(null)")), "does not detach the view listener")
        assertQuiet("a null branch that detaches", detachProblems(nullBranch.replace("imeChanges.forget()", "tileView?.setOnApplyWindowInsetsListener(null)\n imeChanges.forget()")))
        assertEquals("overlay: setImeProbe must detach the view listener in its null branch", emptyList<String>(), detachProblems(realWindow()))
    }

    /** A failure here means a main file names the keyboard signal setting, so the release build would hold the probe. */
    @Test
    fun `no main file of the module names the keyboard signal setting`() {
        assertFires("a use of the setting", settingProblems(mapOf("A.kt" to "val s = KeyboardSignalSetting.OFF\n")), "names the keyboard signal setting")
        assertQuiet("an unrelated name", settingProblems(mapOf("A.kt" to "val s = keyboard\n")))

        assertEquals("overlay: no main file may name the keyboard signal setting", emptyList<String>(), settingProblems(ModuleFiles.mainTexts()))
    }

    /** A failure here means the window of add() no longer carries the plain tile's three flags. */
    @Test
    fun `the add flags are unchanged`() {
        val full = "override fun add(x: Int, y: Int, palette: TruckingPalette): AddOutcome {\n val params = WindowManager.LayoutParams(\n WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or\n WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or\n WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,\n )\n}\n"
        assertQuiet("the three flags in add", flagProblems(full))
        assertFires("a flag left out of add", flagProblems(full.replace("FLAG_NOT_TOUCH_MODAL or\n", "")), "does not build the window with the plain tile's flags")
        assertFires("the flags only in another function", flagProblems("fun show() {\n $addFlags\n}\n"), "no add function")

        assertEquals("overlay: add() must keep the plain tile's three flags", emptyList<String>(), flagProblems(realWindow()))
    }
}
