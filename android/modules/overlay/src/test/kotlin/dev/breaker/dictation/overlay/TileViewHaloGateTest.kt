package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The halo passes of the tile view, read as text: each button is drawn twice, first in the background colour as a
 * halo and then in the control colour, with the halo width set above zero before the first pair and set back to 0f
 * before the second; and the notice text is drawn as a stroke halo in the background colour before its fill in the
 * control colour, on one layout. Each rule runs first on edited copies of the real source, then on the real file.
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

    /** A failure here means a button is drawn without its halo, or a halo pass is drawn in the control colour, so the buttons read on no wallpaper. */
    @Test
    fun `each button is drawn twice with the halo colour first and the control colour last`() {
        val real = ModuleFiles.mainTexts().getValue(view)
        assertQuiet("the real view", ViewDrawingRules.buttonProblems(real))
        assertFires("the first pass in the control colour", ViewDrawingRules.buttonProblems(edit(real, "drawCancel(canvas, TileLayout.cancelCell(s), look.background)", "drawCancel(canvas, TileLayout.cancelCell(s), look.control)")), "first call of drawCancel")
        assertFires("the last pass in the halo colour", ViewDrawingRules.buttonProblems(edit(real, "drawSend(canvas, TileLayout.sendCell(s), look.control)", "drawSend(canvas, TileLayout.sendCell(s), look.background)")), "last call of drawSend")
        assertFires("the halo reset dropped", ViewDrawingRules.buttonProblems(edit(real, "haloExtraPx = 0f\n", "")), "back to 0f")
        assertFires("the halo not set before the first pair", ViewDrawingRules.buttonProblems(edit(real, "haloExtraPx = 2 * ArtMetrics.HALO_DP * density\n", "")), "above zero")
        assertFires("the send button drawn once", ViewDrawingRules.buttonProblems(edit(real, "drawSend(canvas, TileLayout.sendCell(s), look.control)\n", "")), "drawSend is not called exactly twice")
        assertFires("the cross in the send cell", ViewDrawingRules.buttonProblems(edit(real, "drawCancel(canvas, TileLayout.cancelCell(s), look.control)", "drawCancel(canvas, TileLayout.sendCell(s), look.control)")), "drawCancel is not drawn into")
        assertQuiet("a commented halo reset next to the real one", ViewDrawingRules.buttonProblems(edit(real, "haloExtraPx = 0f\n", "// haloExtraPx = 0f\nhaloExtraPx = 0f\n")))

        assertEquals("overlay: $view must draw each button with its halo first and its control colour last", emptyList<String>(), ViewDrawingRules.buttonProblems(real))
    }

    /**
     * A failure here means a button called once is not reported by its count alone, so the halo rule runs on a broken
     * pair, or a button called three times is not reported, so an extra pass slips past the count.
     */
    @Test
    fun `a button called once or three times is reported by its count alone`() {
        val real = ModuleFiles.mainTexts().getValue(view)
        val cancelControl = "drawCancel(canvas, TileLayout.cancelCell(s), look.control)"
        val sendControl = "drawSend(canvas, TileLayout.sendCell(s), look.control)"
        assertEquals(
            "overlay: control: a send button called once must be reported by its count alone",
            listOf("drawSend is not called exactly twice in onDraw"),
            ViewDrawingRules.buttonProblems(edit(real, sendControl + "\n", "")),
        )
        assertEquals(
            "overlay: control: a cancel button called three times must be reported by its count alone",
            listOf("drawCancel is not called exactly twice in onDraw"),
            ViewDrawingRules.buttonProblems(edit(real, cancelControl, "$cancelControl\n$cancelControl")),
        )
        assertEquals(
            "overlay: control: a send button called three times must be reported by its count alone",
            listOf("drawSend is not called exactly twice in onDraw"),
            ViewDrawingRules.buttonProblems(edit(real, sendControl, "$sendControl\n$sendControl")),
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
