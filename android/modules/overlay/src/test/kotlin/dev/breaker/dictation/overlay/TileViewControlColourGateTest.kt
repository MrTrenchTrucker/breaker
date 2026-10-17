package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colour rule follows the two button calls of the tile view: a button call ends in the whole look
 * and is reported when it is given a colour. It is checked on short made-up samples first, and then on
 * the real TileView.kt.
 */
class TileViewControlColourGateTest {

    private val view = "TileView.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** The violations of [check] on one file of [files], or one violation when the file is not there. */
    private fun inFile(files: Map<String, String>, name: String, check: (String) -> List<String>): List<String> =
        files[name]?.let(check) ?: listOf("$name is missing from the main sources")

    /** [text] with [old] replaced by [new]; fails by name when [old] is not in [text], so a sample cannot go stale unseen. */
    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    private val buttonSample = listOf(
        "internal class V {",
        "fun onDraw() {",
        "paint.color = look.background",
        "drawRing(canvas, mic, look.ring)",
        "artDraw.drawControl(canvas, ControlLayout.cancel(s), paint, ControlArt.cancel, look)",
        "artDraw.drawControl(canvas, ControlLayout.send(s), paint, ControlArt.send, look)",
        "}",
        "}",
    ).joinToString("\n") + "\n"

    private val ringLine = "drawRing(canvas, mic, look.ring)\n"
    private val cancelLine = "artDraw.drawControl(canvas, ControlLayout.cancel(s), paint, ControlArt.cancel, look)\n"
    private val sendLine = "artDraw.drawControl(canvas, ControlLayout.send(s), paint, ControlArt.send, look)\n"

    /** A failure here means a button painted with a colour in place of the look is not reported by the colour rule. */
    @Test
    fun `a button drawn with a colour in place of the look is reported by the colour rule`() {
        listOf("ControlArt.cancel" to "cancel", "ControlArt.send" to "send").forEach { (shape, name) ->
            listOf("123", "Color.RED", "tint", "look.control").forEach { replacement ->
                val what = "the $name button drawn with $replacement"
                val problems = AdapterRules.viewColourProblems(edit(buttonSample, "$shape, look)", "$shape, $replacement)"))
                assertFires(what, problems, "a draw call passes a colour")
            }
        }
    }

    /** A failure here means a button drawn with the look is reported, or the real tile view paints a button with a colour. */
    @Test
    fun `a button drawn with the look is quiet and so is the real tile view`() {
        assertQuiet("the sample with buttons drawn with the look", AdapterRules.viewColourProblems(buttonSample))
        assertQuiet(
            "the sample with the cross drawn with the face's look",
            AdapterRules.viewColourProblems(edit(buttonSample, "ControlArt.cancel, look)", "ControlArt.cancel, face.look)")),
        )
        assertEquals(
            "overlay: $view must draw its buttons with the face's look",
            emptyList<String>(),
            inFile(ModuleFiles.mainTexts(), view, AdapterRules::viewColourProblems),
        )
    }

    /** A failure here means a button call is not counted as a draw call, so a sample that keeps only the buttons is reported wrongly. */
    @Test
    fun `a button call alone counts as a draw call with a colour`() {
        assertQuiet("the two buttons without the ring", AdapterRules.viewColourProblems(edit(buttonSample, ringLine, "")))
        val noCalls = edit(edit(edit(buttonSample, ringLine, ""), cancelLine, ""), sendLine, "")
        assertFires("the sample with no button and no ring", AdapterRules.viewColourProblems(noCalls), "no draw call with a colour")
    }
}
