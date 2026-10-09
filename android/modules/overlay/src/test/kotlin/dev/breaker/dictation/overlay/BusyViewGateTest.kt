package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The busy face's draw and pulse hooks in the tile view, read as text because the view cannot run on a plain
 * JVM: the slash is skipped unless the state is MIC_BUSY and is coloured with the ring colour, the busy pulse
 * callback hands its alpha to the ring only while the busy wish holds, and refreshPulse starts and stops the
 * busy pulse through the same pure rule as the armed pulse.
 *
 * Each rule runs first on short made-up samples (a bad one must be reported, a good one must not) and then on
 * the real view. A file that cannot be found is a violation, never a pass.
 */
class BusyViewGateTest {

    private val view = "TileView.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    /** The problems with the slash in `drawGlyph`: skipped unless the state is MIC_BUSY, and coloured with the ring colour. */
    private fun slashDrawProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "drawGlyph")
            ?: return listOf("TileView has no drawGlyph function with a block body")
        val skip = Regex("\\bif\\s*\\(\\s*rect\\.role\\s*==\\s*GlyphRole\\.SLASH\\s*&&\\s*state\\s*!=\\s*TileState\\.MIC_BUSY\\s*\\)\\s*continue\\b")
        val colour = Regex("\\bGlyphRole\\.SLASH\\s*->\\s*look\\.ring\\b")
        return listOfNotNull(
            "drawGlyph does not skip the slash unless the state is MIC_BUSY".takeUnless { skip.containsMatchIn(body) },
            "drawGlyph does not colour the slash with look.ring".takeUnless { colour.containsMatchIn(body) },
        )
    }

    /** The problems with the busy pulse callback: it hands the animator's alpha to the ring while the busy wish holds, and redraws. */
    private fun busyCallbackProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val head = Regex("\\bbusyPulse\\s*\\{").find(code) ?: return listOf("TileView has no busyPulse { ... } callback")
        val open = head.range.last
        val close = SourceText.closeOf(code, open)
        if (close < 0) return listOf("the busyPulse callback never closes")
        val body = code.substring(open + 1, close)
        val arrow = Regex("^\\s*alpha\\s*->").find(body) ?: return listOf("the busyPulse callback does not take alpha as its parameter")
        val statements = body.substring(arrow.range.last + 1)
            .split(';', '\n').map { it.trim().replace(Regex("\\s+"), " ") }.filter { it.isNotEmpty() }
        return listOfNotNull(
            "the busyPulse callback does not store alpha into pulseAlpha while busyWanted holds"
                .takeUnless { statements.contains("if (busyWanted) pulseAlpha = alpha") },
            "the busyPulse callback does not call invalidate()".takeUnless { statements.contains("invalidate()") },
        )
    }

    /** The problems with refreshPulse for the busy pulse: it starts when the pure rule says so for the busy wish, and stops otherwise. */
    private fun busyRefreshProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "refreshPulse")
            ?: return listOf("TileView has no refreshPulse function with a block body")
        val busy = Regex("\\bif\\s*\\(\\s*pulseShouldRun\\(\\s*busyWanted\\s*,[^\\n]*ArmedPulse\\.animationsOn\\(\\)\\s*\\)\\s*\\)\\s*busyPulse\\.start\\(\\)\\s*else\\s*busyPulse\\.stop\\(\\)")
        return listOfNotNull(
            "refreshPulse does not start and stop busyPulse through pulseShouldRun(busyWanted, ..., ArmedPulse.animationsOn())"
                .takeUnless { busy.containsMatchIn(body) },
        )
    }

    private val glyphSample = "private fun drawGlyph(canvas: Canvas, cell: TileRect, look: TileLook, state: TileState) {\n" +
        "for (rect in TileGlyph.rects) {\n" +
        "if (rect.role == GlyphRole.SLASH && state != TileState.MIC_BUSY) continue\n" +
        "paint.color = when (rect.role) {\n" +
        "GlyphRole.BODY -> look.glyph\n" +
        "GlyphRole.SLASH -> look.ring\n" +
        "}\n}\n}\n"

    /** A failure means the slash is drawn in another state, is never drawn, or is not in the ring colour. */
    @Test
    fun `the slash is skipped unless the state is MIC_BUSY and is drawn in the ring colour`() {
        assertQuiet("the real shape", slashDrawProblems(glyphSample))
        assertFires("the skip always true", slashDrawProblems(edit(glyphSample, "state != TileState.MIC_BUSY", "state != null")), "does not skip the slash")
        assertFires("the skip removed", slashDrawProblems(edit(glyphSample, "if (rect.role == GlyphRole.SLASH && state != TileState.MIC_BUSY) continue\n", "")), "does not skip the slash")
        assertFires("the skip for another state", slashDrawProblems(edit(glyphSample, "state != TileState.MIC_BUSY", "state != TileState.FAILED")), "does not skip the slash")
        assertFires("the slash in the glyph colour", slashDrawProblems(edit(glyphSample, "SLASH -> look.ring", "SLASH -> look.glyph")), "does not colour the slash with look.ring")
        assertFires("no slash colour", slashDrawProblems(edit(glyphSample, "GlyphRole.SLASH -> look.ring\n", "")), "does not colour the slash with look.ring")
        assertFires("no drawGlyph", slashDrawProblems("val x = 1\n"), "no drawGlyph function")
        assertQuiet("a wrong line only in a comment", slashDrawProblems(edit(glyphSample, "if (rect.role", "// SLASH -> look.glyph\nif (rect.role")))
        assertEquals("overlay: $view must skip the slash outside MIC_BUSY and colour it with the ring", emptyList<String>(), slashDrawProblems(ModuleFiles.mainTexts().getValue(view)))
    }

    private val callbackSample = "private val busyPulse = busyPulse { alpha -> if (busyWanted) pulseAlpha = alpha; invalidate() }\n"

    /** A failure means the busy pulse never reaches the ring, reaches it when it is not wanted, or does not redraw the view. */
    @Test
    fun `the busy pulse callback gives its alpha to the ring while the busy wish holds and redraws`() {
        assertQuiet("the sample", busyCallbackProblems(callbackSample))
        assertQuiet("the redraw first", busyCallbackProblems(edit(callbackSample, "if (busyWanted) pulseAlpha = alpha; invalidate()", "invalidate(); if (busyWanted) pulseAlpha = alpha")))
        assertFires("the store removed", busyCallbackProblems(edit(callbackSample, "if (busyWanted) pulseAlpha = alpha; ", "")), "does not store alpha into pulseAlpha")
        assertFires("the store without the wish", busyCallbackProblems(edit(callbackSample, "if (busyWanted) pulseAlpha", "pulseAlpha")), "while busyWanted holds")
        assertFires("a constant stored", busyCallbackProblems(edit(callbackSample, "pulseAlpha = alpha", "pulseAlpha = 1f")), "does not store alpha into pulseAlpha")
        assertFires("the redraw removed", busyCallbackProblems(edit(callbackSample, "; invalidate()", "")), "does not call invalidate()")
        assertFires("no callback", busyCallbackProblems("val x = 1\n"), "no busyPulse")
        assertEquals("overlay: $view must hand the busy alpha to the ring while busyWanted holds", emptyList<String>(), busyCallbackProblems(ModuleFiles.mainTexts().getValue(view)))
    }

    private val refreshSample = "private fun refreshPulse() {\n" +
        "if (pulseShouldRun(wanted, attached, windowVisible, screenOn, ArmedPulse.animationsOn())) pulse.start() else pulse.stop()\n" +
        "if (pulseShouldRun(busyWanted, attached, windowVisible, screenOn, ArmedPulse.animationsOn())) busyPulse.start() else busyPulse.stop()\n" +
        "}\n"

    /** A failure means the busy pulse is never started, is started without the rule, or is not stopped when the rule says no. */
    @Test
    fun `refreshPulse starts and stops the busy pulse through the pure rule`() {
        assertQuiet("the sample", busyRefreshProblems(refreshSample))
        assertFires("always stopped", busyRefreshProblems(edit(refreshSample, "if (pulseShouldRun(busyWanted, attached, windowVisible, screenOn, ArmedPulse.animationsOn())) busyPulse.start() else busyPulse.stop()", "busyPulse.stop()")), "does not start and stop busyPulse")
        assertFires("always started", busyRefreshProblems(edit(refreshSample, "if (pulseShouldRun(busyWanted, attached, windowVisible, screenOn, ArmedPulse.animationsOn())) busyPulse.start() else busyPulse.stop()", "busyPulse.start()")), "does not start and stop busyPulse")
        assertFires("the armed wish used", busyRefreshProblems(edit(refreshSample, "pulseShouldRun(busyWanted,", "pulseShouldRun(wanted,")), "does not start and stop busyPulse")
        assertFires("the animator switch dropped", busyRefreshProblems(edit(refreshSample, "screenOn, ArmedPulse.animationsOn())) busyPulse", "screenOn, true)) busyPulse")), "does not start and stop busyPulse")
        assertFires("no refreshPulse", busyRefreshProblems("val x = 1\n"), "no refreshPulse")
        assertEquals("overlay: $view must start and stop the busy pulse through pulseShouldRun", emptyList<String>(), busyRefreshProblems(ModuleFiles.mainTexts().getValue(view)))
    }
}
