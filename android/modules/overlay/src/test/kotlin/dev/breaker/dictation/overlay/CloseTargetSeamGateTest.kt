package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The close target passes the width and the height of its window through three hops. Both are Int
 * parameters of the same type, so a swap compiles, and no JVM test reads the window manager code. These
 * checks read the source text of the three hops instead. Each check is a pure function from source text to
 * a list of problems. Every test runs its check on a sample that breaks it (the problem must be reported),
 * on a sample that keeps it (no problem), and then on the real file. Comments are blanked and string
 * literals are kept.
 */
class CloseTargetSeamGateTest {

    private val windowFile = "WindowManagerTileWindow.kt"
    private val targetFile = "CloseTargetWindow.kt"
    private val dragFile = "CloseTarget.kt"
    private val forwardedCall = "return closeTargetWindow.show(x, y, widthPx, heightPx, palette)"
    private val frameLine = "val (left, top, width, height) = frameFor(x, y, widthPx, heightPx)"
    private val layoutEnd = "WindowManager.LayoutParams("
    private val showArguments = listOf(
        "cx,",
        "cy,",
        "CloseTargetShape.windowWidthPx(bounds),",
        "CloseTargetShape.windowHeightPx(bounds),",
        "TruckingTokens.DARK,",
    )

    /** The window's showCloseTarget is one statement that forwards its four arguments to the target window. */
    private fun forwardProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "showCloseTarget")
            ?: return listOf("no showCloseTarget function with a block body was found")
        val inner = body.removePrefix("{").removeSuffix("}").trim()
        return if (inner == forwardedCall) {
            emptyList()
        } else {
            listOf("showCloseTarget does not forward exactly one call: '$inner'")
        }
    }

    /** The layout params of show take the frame width, then the frame height, and the frame is destructured in that order. */
    private fun layoutProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "show")
            ?: return listOf("no show function with a block body was found")
        val lines = body.lines().map { it.trim() }
        val start = lines.indexOfFirst { it.endsWith(layoutEnd) }
        if (start < 0) {
            return listOf("show does not build a WindowManager.LayoutParams call")
        }
        val first = lines.getOrNull(start + 1)
        val second = lines.getOrNull(start + 2)
        val order = if (first == "width," && second == "height,") {
            emptyList<String>()
        } else {
            listOf("the layout params take '$first' and '$second' first, not width then height")
        }
        val frame = if (frameLine in lines) {
            emptyList<String>()
        } else {
            listOf("show does not destructure the frame as left, top, width, height")
        }
        return order + frame
    }

    /** The drop step passes the centre, the window width, the window height and the dark palette, one argument per line. */
    private fun dragStepProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "onDragStep")
            ?: return listOf("no onDragStep function with a block body was found")
        val lines = body.lines().map { it.trim() }
        val start = lines.indexOfFirst { it == "window.showCloseTarget(" }
        if (start < 0) {
            return listOf("onDragStep does not call window.showCloseTarget(")
        }
        val args = lines.drop(start + 1).takeWhile { it != ")" }
        return if (args == showArguments) {
            emptyList()
        } else {
            listOf("the showCloseTarget arguments are $args, expected $showArguments")
        }
    }

    private fun mainText(file: String): String = ModuleFiles.mainTexts().getValue(file)

    /** The text with old replaced by new. It fails by name when old is not in the text, so a sample cannot go stale unseen. */
    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** A failure here means the window passes the window size in another order, drops the palette, or calls another function. */
    @Test
    fun `the window forwards the centre and the width and height and the palette in order`() {
        val real = mainText(windowFile)
        assertFires(
            "width and height swapped",
            forwardProblems(edit(real, forwardedCall, "return closeTargetWindow.show(x, y, heightPx, widthPx, palette)")),
            "does not forward",
        )
        assertFires(
            "palette dropped",
            forwardProblems(edit(real, forwardedCall, "return closeTargetWindow.show(x, y, widthPx, heightPx)")),
            "does not forward",
        )
        assertFires(
            "another callee",
            forwardProblems(edit(real, forwardedCall, "return closeTargetWindow.hide(x, y, widthPx, heightPx, palette)")),
            "does not forward",
        )
        assertFires(
            "a second statement",
            forwardProblems(edit(real, forwardedCall, forwardedCall + "\n        closeTargetWindow.hide()")),
            "does not forward",
        )
        assertQuiet(
            "a self-contained window that forwards its arguments",
            forwardProblems(
                "override fun showCloseTarget(x: Int, y: Int, widthPx: Int, heightPx: Int, palette: TruckingPalette): Boolean {\n" +
                    "        " + forwardedCall + "\n" +
                    "    }\n",
            ),
        )

        assertEquals("overlay: $windowFile must forward the centre, width, height and palette in order", emptyList<String>(), forwardProblems(real))
    }

    /** A failure here means the layout params take the window size in another order, or the frame is destructured in another order. */
    @Test
    fun `the layout params take the frame width then height and the frame is destructured in that order`() {
        val real = mainText(targetFile)
        assertFires(
            "width and height swapped in the layout params",
            layoutProblems(edit(real, "            width,\n            height,\n", "            height,\n            width,\n")),
            "not width then height",
        )
        assertFires(
            "the first layout argument renamed",
            layoutProblems(edit(real, "            width,\n", "            widthPx,\n")),
            "not width then height",
        )
        assertFires(
            "the frame destructured with height before width",
            layoutProblems(edit(real, frameLine, "val (left, top, height, width) = frameFor(x, y, widthPx, heightPx)")),
            "destructure",
        )
        assertQuiet(
            "a self-contained show with the frame and the layout params in order",
            layoutProblems(
                "fun show(x: Int, y: Int, widthPx: Int, heightPx: Int, palette: TruckingPalette): Boolean {\n" +
                    "        " + frameLine + "\n" +
                    "        val params = WindowManager.LayoutParams(\n" +
                    "            width,\n" +
                    "            height,\n" +
                    "        )\n" +
                    "    }\n",
            ),
        )

        assertEquals("overlay: $targetFile must build the layout params as width then height", emptyList<String>(), layoutProblems(real))
    }

    /** A failure here means the drop step shows the box with the width and the height swapped, or with another palette. */
    @Test
    fun `the drop step shows the box with width then height and the dark palette in one argument per line`() {
        val real = mainText(dragFile)
        assertFires(
            "width and height swapped",
            dragStepProblems(
                edit(
                    real,
                    "            CloseTargetShape.windowWidthPx(bounds),\n            CloseTargetShape.windowHeightPx(bounds),\n",
                    "            CloseTargetShape.windowHeightPx(bounds),\n            CloseTargetShape.windowWidthPx(bounds),\n",
                ),
            ),
            "arguments are",
        )
        assertFires(
            "the theme palette in place of the dark palette",
            dragStepProblems(edit(real, "            TruckingTokens.DARK,\n", "            TruckingTokens.palette(theme),\n")),
            "arguments are",
        )
        assertFires(
            "the centre on one line",
            dragStepProblems(edit(real, "            cx,\n            cy,\n", "            cx, cy,\n")),
            "arguments are",
        )
        assertQuiet(
            "a self-contained drop step that passes the arguments in order",
            dragStepProblems(
                "internal fun onDragStep(window: TileWindow, theme: ThemeMode) {\n" +
                    "        val bounds = window.usableBounds()\n" +
                    "        val (cx, cy) = targetCentreInPixels(bounds)\n" +
                    "        window.showCloseTarget(\n" +
                    "            cx,\n" +
                    "            cy,\n" +
                    "            CloseTargetShape.windowWidthPx(bounds),\n" +
                    "            CloseTargetShape.windowHeightPx(bounds),\n" +
                    "            TruckingTokens.DARK,\n" +
                    "        )\n" +
                    "    }\n",
            ),
        )

        assertEquals("overlay: $dragFile must show the box with width then height and the dark palette", emptyList<String>(), dragStepProblems(real))
    }
}
