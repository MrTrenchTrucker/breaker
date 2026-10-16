package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The busy sign and the busy pulse of the tile, read as text because the view cannot run on a plain JVM.
 * The busy sign is drawn only in the MIC_BUSY state, as a dark outline under the red ring colour, as a circle
 * and a flat slash; its radius and slash ends come from the busy sign of the geometry file. The busy pulse callback hands
 * its alpha to the ring only while the busy wish holds, and refreshPulse starts and stops the busy pulse
 * through the same pure rule as the armed pulse.
 *
 * Each rule runs first on short made-up samples (a bad one must be reported, a good one must not) and then on
 * the real files. A file that cannot be found is a violation, never a pass.
 */
class BusyViewGateTest {

    private val view = "TileView.kt"
    private val art = "TileArtDraw.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    /** The problems with the call of drawBusy in drawArt: one call, under face.state == TileState.MIC_BUSY, in the ring colour. */
    private fun guardProblems(source: String): List<String> {
        val draw = AdapterRules.blockBodyOf(SourceText.code(source), "drawArt")
            ?: return listOf("TileArtDraw has no drawArt function with a block body")
        val guarded = Regex("\\bif\\s*\\(\\s*face\\.state\\s*==\\s*TileState\\.MIC_BUSY\\s*\\)\\s*drawBusy\\s*\\(\\s*canvas\\s*,\\s*paint\\s*,\\s*look\\s*,\\s*pulseAlpha\\s*\\)")
        val calls = Regex("\\bdrawBusy\\s*\\(").findAll(draw).count()
        return listOfNotNull(
            "drawArt does not call drawBusy under face.state == TileState.MIC_BUSY with the look".takeUnless { guarded.containsMatchIn(draw) },
            "drawArt calls drawBusy more than once".takeIf { calls != 1 },
        )
    }

    /** The problems with drawBusy: the sign read from ArtGeometry.busySign(), a circle by drawCircle at the busy centre, a slash by drawLine, the dark outline colour and its stroke width for the dark pass and the ring colour and ring width for the red one, the ordering (dark pass before the red ring), exactly two circles and two slashes, a flat cap before the slash and a restored cap after it, no rectangle, no arithmetic and no square root of its own. */
    private fun shapeProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "drawBusy")
            ?: return listOf("TileArtDraw has no drawBusy function with a block body")
        val slash = Regex("\\bcanvas\\.drawLine\\s*\\(").find(body)?.range?.first ?: -1
        val caps = Regex("\\bpaint\\.strokeCap\\s*=\\s*Paint\\.Cap\\.(\\w+)").findAll(body).map { it.range.first to it.groupValues[1] }.toList()
        val before = caps.filter { it.first < slash }.map { it.second }
        val after = caps.filter { it.first > slash }.map { it.second }
        val darkColour = body.indexOf("paint.color = look.busyOutline")
        val darkWidth = body.indexOf("paint.strokeWidth = busy.outlineStroke")
        val redColour = body.indexOf("paint.color = ringDrawColor(look.ring, pulseAlpha)")
        val redWidth = body.indexOf("paint.strokeWidth = busy.stroke")
        val firstCircle = body.indexOf("canvas.drawCircle(")
        val firstSlash = body.indexOf("canvas.drawLine(")
        val secondCircle = body.indexOf("canvas.drawCircle(", firstCircle + 1)
        val circleCount = body.split("canvas.drawCircle(").size - 1
        val slashCount = body.split("canvas.drawLine(").size - 1
        return listOfNotNull(
            "drawBusy does not read its sign from ArtGeometry.busySign()".takeUnless { Regex("\\bval\\s+busy\\s*=\\s*ArtGeometry\\.busySign\\(\\s*\\)").containsMatchIn(body) },
            "drawBusy does not draw the circle with canvas.drawCircle".takeUnless { Regex("\\bcanvas\\.drawCircle\\s*\\(\\s*busy\\.centreX\\s*,\\s*busy\\.centreY\\s*,\\s*busy\\.radius\\s*,\\s*paint\\s*\\)").containsMatchIn(body) },
            "drawBusy does not draw the slash ends from the busy sign with canvas.drawLine".takeUnless {
                Regex("\\bcanvas\\.drawLine\\s*\\(\\s*busy\\.slashFromX\\s*,\\s*busy\\.slashFromY\\s*,\\s*busy\\.slashToX\\s*,\\s*busy\\.slashToY\\s*,\\s*paint\\s*\\)").containsMatchIn(body)
            },
            "drawBusy does not draw the slash with canvas.drawLine".takeIf { slash < 0 },
            "drawBusy does not set the red pass colour from ringDrawColor(look.ring, pulseAlpha)".takeUnless { Regex("\\bpaint\\.color\\s*=\\s*ringDrawColor\\(\\s*look\\.ring\\s*,\\s*pulseAlpha\\s*\\)").containsMatchIn(body) },
            "drawBusy does not use busy.stroke as its stroke width".takeUnless { Regex("\\bpaint\\.strokeWidth\\s*=\\s*busy\\.stroke\\b").containsMatchIn(body) },
            "drawBusy does not set strokeCap BUTT before the slash".takeUnless { before.lastOrNull() == "BUTT" },
            "drawBusy does not restore strokeCap after the slash".takeUnless { after.isNotEmpty() && after.last() != "BUTT" },
            "drawBusy draws a rectangle".takeIf { Regex("\\bdrawRect\\s*\\(").containsMatchIn(body) },
            "drawBusy computes a number of its own, which must come from the busy sign".takeIf { Regex("[-+*/]").containsMatchIn(body) },
            "drawBusy takes a square root".takeIf { Regex("\\bsqrt\\b").containsMatchIn(body) },
            "drawBusy does not set the dark pass colour to look.busyOutline".takeIf { darkColour < 0 },
            "drawBusy does not set the dark pass stroke to busy.outlineStroke".takeIf { darkWidth < 0 },
            "drawBusy does not draw the dark outline pass before the red ring".takeIf {
                  !(darkColour in 0 until firstCircle && darkWidth in 0 until firstCircle
                    && redColour in (firstSlash + 1) until secondCircle && redWidth in (firstSlash + 1) until secondCircle)
                },
            "drawBusy does not draw the circle and slash twice (dark outline pass and red ring)".takeIf { circleCount != 2 || slashCount != 2 },
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

    /** A small drawArt and drawBusy as the art drawing has them after the change: the sign comes from the geometry file, the dark outline is drawn under the red ring, and the last cap set is the ring's own cap. */
    private val busySample = "private fun drawArt(canvas: Canvas, cell: TileRect, paint: Paint, face: TileFace, pulseAlpha: Float) {\n" +
        "val look = face.look\n" +
        "inCell(canvas, cell) {\n" +
        "if (face.state == TileState.MIC_BUSY) drawBusy(canvas, paint, look, pulseAlpha)\n" +
        "}\n" +
        "}\n" +
        "private fun drawBusy(canvas: Canvas, paint: Paint, look: TileLook, pulseAlpha: Float) {\n" +
        "val busy = ArtGeometry.busySign()\n" +
        "paint.style = Paint.Style.STROKE\n" +
        "paint.color = look.busyOutline\n" +
        "paint.strokeWidth = busy.outlineStroke\n" +
        "canvas.drawCircle(busy.centreX, busy.centreY, busy.radius, paint)\n" +
        "paint.strokeCap = Paint.Cap.BUTT\n" +
        "canvas.drawLine(busy.slashFromX, busy.slashFromY, busy.slashToX, busy.slashToY, paint)\n" +
        "paint.color = ringDrawColor(look.ring, pulseAlpha)\n" +
        "paint.strokeWidth = busy.stroke\n" +
        "canvas.drawCircle(busy.centreX, busy.centreY, busy.radius, paint)\n" +
        "paint.strokeCap = Paint.Cap.BUTT\n" +
        "canvas.drawLine(busy.slashFromX, busy.slashFromY, busy.slashToX, busy.slashToY, paint)\n" +
        "paint.strokeCap = Paint.Cap.ROUND\n" +
        "}\n"

     /** A failure means the busy sign is drawn outside the MIC_BUSY state, with another look, or by more than one call. */
    @Test
    fun `the busy sign is drawn only for MIC_BUSY`() {
        assertQuiet("the sample", guardProblems(busySample))
        assertFires("the guard removed", guardProblems(edit(busySample, "if (face.state == TileState.MIC_BUSY) drawBusy", "drawBusy")), "drawArt does not call drawBusy under")
        assertFires("the guard for another state", guardProblems(edit(busySample, "face.state == TileState.MIC_BUSY", "face.state == TileState.FAILED")), "drawArt does not call drawBusy under")
        assertFires("the guard only in a comment", guardProblems(edit(busySample, "if (face.state == TileState.MIC_BUSY) drawBusy(canvas, paint, look, pulseAlpha)\n", "// if (face.state == TileState.MIC_BUSY) drawBusy(canvas, paint, look, pulseAlpha)\n")), "drawArt does not call drawBusy under")
        assertFires("the colour from look.control", guardProblems(edit(busySample, "drawBusy(canvas, paint, look, pulseAlpha)", "drawBusy(canvas, paint, look.control, pulseAlpha)")), "drawArt does not call drawBusy under")
        assertFires("a second call without the guard", guardProblems(edit(busySample, "val look = face.look\n", "val look = face.look\ndrawBusy(canvas, paint, look)\n")), "calls drawBusy more than once")
        assertEquals("overlay: $art must call drawBusy only under MIC_BUSY in drawArt, with the look", emptyList<String>(), guardProblems(ModuleFiles.mainTexts().getValue(art)))
    }

    /** A failure means the busy sign is not a circle and a flat slash in the ring colour at the busy stroke width, the flat cap is not set before the slash and restored after it, or drawBusy takes its numbers from anywhere but the geometry file. */
    @Test
    fun `the busy sign is a circle and a flat slash in the ring colour with its numbers from the geometry`() {
        assertQuiet("the sample", shapeProblems(busySample))
        assertFires("the colour from the control", shapeProblems(edit(busySample, "paint.color = ringDrawColor(look.ring, pulseAlpha)", "paint.color = look.control")), "does not set the red pass colour")
        assertFires("the width from the margin", shapeProblems(edit(busySample, "paint.strokeWidth = busy.stroke", "paint.strokeWidth = ArtMetrics.BUSY_MARGIN_UNITS")), "does not use busy.stroke")
        assertFires("the cap left flat after the slash", shapeProblems(edit(busySample, "paint.strokeCap = Paint.Cap.ROUND", "paint.strokeCap = Paint.Cap.BUTT")), "does not restore strokeCap")
        assertFires("no flat cap before the slash", shapeProblems(edit(busySample, "paint.strokeCap = Paint.Cap.BUTT\n", "")), "does not set strokeCap BUTT before the slash")
        assertFires("a rectangle in the function", shapeProblems(edit(busySample, "canvas.drawCircle(busy.centreX, busy.centreY, busy.radius, paint)\n", "canvas.drawCircle(busy.centreX, busy.centreY, busy.radius, paint)\ncanvas.drawRect(0f, 0f, busy.centreX, busy.centreY, paint)\n")), "draws a rectangle")
        assertFires("no circle", shapeProblems(edit(busySample, "canvas.drawCircle(", "canvas.drawOval(")), "does not draw the circle")
        assertFires("no slash", shapeProblems(edit(busySample, "canvas.drawLine(", "canvas.drawPoint(")), "does not draw the slash")
        assertFires("the sign not read from the geometry", shapeProblems(edit(busySample, "val busy = ArtGeometry.busySign()", "val busy = busySignHere()")), "does not read its sign from ArtGeometry.busySign()")
        assertFires("a slash end computed in the function", shapeProblems(edit(busySample, "canvas.drawLine(busy.slashFromX, busy.slashFromY, busy.slashToX, busy.slashToY, paint)", "canvas.drawLine(busy.slashFromX / 2f, busy.slashFromY, busy.slashToX, busy.slashToY, paint)")), "computes a number of its own")
        assertFires("a square root left in the function", shapeProblems(edit(busySample, "val busy = ArtGeometry.busySign()\n", "val busy = ArtGeometry.busySign()\nval reach = busy.radius / sqrt(2f)\n")), "square root")
        assertFires("the circle centred off the box", shapeProblems(edit(busySample, "canvas.drawCircle(busy.centreX, busy.centreY, busy.radius, paint)", "canvas.drawCircle(busy.radius, busy.centreX, busy.radius, paint)")), "does not draw the circle")
        assertFires("the dark pass colour from danger", shapeProblems(edit(busySample, "paint.color = look.busyOutline", "paint.color = look.danger")), "does not set the dark pass colour")
        assertFires("the dark pass width from the ring", shapeProblems(edit(busySample, "paint.strokeWidth = busy.outlineStroke", "paint.strokeWidth = busy.stroke")), "does not set the dark pass stroke")
        assertFires("the dark outline pass deleted", shapeProblems(edit(busySample, "paint.color = look.busyOutline\npaint.strokeWidth = busy.outlineStroke\ncanvas.drawCircle(busy.centreX, busy.centreY, busy.radius, paint)\npaint.strokeCap = Paint.Cap.BUTT\ncanvas.drawLine(busy.slashFromX, busy.slashFromY, busy.slashToX, busy.slashToY, paint)\n", "")), "does not draw the dark outline pass before the red ring")
        assertFires("a circle or slash missing", shapeProblems(edit(busySample, "paint.color = look.busyOutline\npaint.strokeWidth = busy.outlineStroke\ncanvas.drawCircle(busy.centreX, busy.centreY, busy.radius, paint)\npaint.strokeCap = Paint.Cap.BUTT\ncanvas.drawLine(busy.slashFromX, busy.slashFromY, busy.slashToX, busy.slashToY, paint)\n", "")), "does not draw the circle and slash twice")
        assertEquals("overlay: $art must draw the busy sign from the geometry as a dark outline under a circle and a flat slash in the ring colour", emptyList<String>(), shapeProblems(ModuleFiles.mainTexts().getValue(art)))
    }

    /** A failure means the ring stroke is no longer 92 art units (the old BUSY_STROKE_UNITS / busy margin 60 are gone). */
    @Test
    fun `the busy ring stroke is 92 and the dark outline stroke is 124 art units`() {
        assertEquals("overlay: the busy ring stroke expected 92 art units", 92f, ArtMetrics.BUSY_STROKE, 0f)
        assertEquals("overlay: the busy outline stroke expected 124 art units", 124f, ArtMetrics.BUSY_OUTLINE_STROKE, 0f)
        assertEquals("overlay: the busy outline shade factor expected -0.85f", -0.85f, ArtFactors.BUSY_OUTLINE_SHADE, 0f)
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
