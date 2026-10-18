package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The buttons and the notice text of the tile view, read as text: each button is one drawControl call into its own
 * cell, the cross first, and drawControl draws the button as a filled shape with a trim outline. The notice text is
 * drawn as a stroke halo in the background colour before its fill in the control colour, on one layout. Only the
 * notice has a halo. Each rule runs first on edited copies of the real source, then on the real file.
 */
class TileViewHaloGateTest {

    private val view = "TileView.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** [text] with [old] replaced by [new]; fails by name when [old] is not in [text], so a sample cannot go stale unseen. */
    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    /**
     * A failure here means a button is drawn into the other cell, with the other shape, out of order, twice or not at
     * all, or beside an old drawCancel, drawSend or drawLine call, so a button reads in the wrong place or not at all.
     */
    @Test
    fun `each button is one drawControl call into its own cell and the cross comes first`() {
        val real = ModuleFiles.mainTexts().getValue(view)
        val cancelCall = "artDraw.drawControl(canvas, ControlLayout.cancel(s), paint, ControlArt.cancel, look)"
        val sendCall = "artDraw.drawControl(canvas, ControlLayout.send(s), paint, ControlArt.send, look)"
        assertQuiet("the real view", ViewDrawingRules.buttonProblems(real))
        assertFires("the cross in the send cell", ViewDrawingRules.buttonProblems(edit(real, cancelCall, "artDraw.drawControl(canvas, ControlLayout.send(s), paint, ControlArt.cancel, look)")), "is not drawn into ControlLayout.cancel(s)")
        assertFires("the check in the cancel cell", ViewDrawingRules.buttonProblems(edit(real, sendCall, "artDraw.drawControl(canvas, ControlLayout.cancel(s), paint, ControlArt.send, look)")), "is not drawn into ControlLayout.send(s)")
        assertFires("the cross with the check shape", ViewDrawingRules.buttonProblems(edit(real, sendCall, "artDraw.drawControl(canvas, ControlLayout.send(s), paint, ControlArt.cancel, look)")), "the check is not drawn exactly once")
        assertFires("the check call dropped", ViewDrawingRules.buttonProblems(edit(real, sendCall + "\n", "")), "the check is not drawn exactly once")
        assertFires("the cross call tripled", ViewDrawingRules.buttonProblems(edit(real, cancelCall, "$cancelCall\n$cancelCall")), "the cross is not drawn exactly once")
        assertFires("the cross after the check", ViewDrawingRules.buttonProblems(edit(edit(real, cancelCall + "\n", ""), sendCall, "$sendCall\n$cancelCall")), "the check is drawn before the cross")
        assertFires("an old drawCancel call left in", ViewDrawingRules.buttonProblems(edit(real, cancelCall, "$cancelCall\ndrawCancel(canvas, TileLayout.cancelCell(s), CANCEL_STROKE_UNITS, look.danger)")), "drawCancel is still drawn in onDraw")
        assertQuiet("a commented call next to the real one", ViewDrawingRules.buttonProblems(edit(real, cancelCall, "// $cancelCall\n$cancelCall")))
        assertQuiet("the arguments on separate lines with a trailing comma", ViewDrawingRules.buttonProblems(edit(real, cancelCall, "artDraw.drawControl(\n canvas,\n ControlLayout.cancel(s),\n paint,\n ControlArt.cancel,\n look,\n)")))

        assertEquals("overlay: $view must draw each button once with drawControl into its own cell, the cross first", emptyList<String>(), ViewDrawingRules.buttonProblems(real))
    }

    /**
     * A failure here means a button drawn once is not reported by its count alone, so the halo rule runs on a broken
     * pair, or a button drawn three times is not reported, so an extra call slips past the count.
     */
    @Test
    fun `a button drawn once or three times by drawControl is reported by its count alone`() {
        val real = ModuleFiles.mainTexts().getValue(view)
        val cancelCall = "artDraw.drawControl(canvas, ControlLayout.cancel(s), paint, ControlArt.cancel, look)"
        val sendCall = "artDraw.drawControl(canvas, ControlLayout.send(s), paint, ControlArt.send, look)"
        assertEquals(
            "overlay: control: a check drawn once must be reported by its count alone",
            listOf("the check is not drawn exactly once with drawControl in onDraw"),
            ViewDrawingRules.buttonProblems(edit(real, sendCall + "\n", "")),
        )
        assertEquals(
            "overlay: control: a cross drawn three times must be reported by its count alone",
            listOf("the cross is not drawn exactly once with drawControl in onDraw"),
            ViewDrawingRules.buttonProblems(edit(real, cancelCall, "$cancelCall\n$cancelCall")),
        )
        assertEquals(
            "overlay: control: a check drawn three times must be reported by its count alone",
            listOf("the check is not drawn exactly once with drawControl in onDraw"),
            ViewDrawingRules.buttonProblems(edit(real, sendCall, "$sendCall\n$sendCall")),
        )
    }

    /** A failure here means the notice text is drawn without its halo, or its halo is drawn over the text, so the notice reads on no wallpaper. */
    @Test
    fun `the notice text is drawn as a halo in the background colour and then in the control colour`() {
        val real = ModuleFiles.mainTexts().getValue(view)
        assertQuiet("the real notice", ViewDrawingRules.noticeHaloProblems(real))
        assertFires("the halo in the control colour", ViewDrawingRules.noticeHaloProblems(edit(real, "textPaint.color = face.look.background", "textPaint.color = face.look.control")), "halo")
        assertFires("the stroke not first", ViewDrawingRules.noticeHaloProblems(edit(real, "textPaint.style = Paint.Style.STROKE", "textPaint.style = Paint.Style.FILL")), "halo")
        assertFires("the fill reset dropped", ViewDrawingRules.noticeHaloProblems(edit(real, "textPaint.style = Paint.Style.FILL\n", "")), "FILL")
        assertFires("the layout built with six arguments", ViewDrawingRules.noticeHaloProblems(edit(real, "obtain(text, 0, text.length, textPaint, textWidth)", "obtain(text, 0, text.length, textPaint, textWidth, 1)")), "five-argument")
        assertQuiet("a commented fill reset next to the real one", ViewDrawingRules.noticeHaloProblems(edit(real, "textPaint.style = Paint.Style.FILL\n", "// textPaint.style = Paint.Style.FILL\ntextPaint.style = Paint.Style.FILL\n")))

        assertEquals("overlay: $view must draw its notice text as a halo and then the text", emptyList<String>(), ViewDrawingRules.noticeHaloProblems(real))
    }
}
