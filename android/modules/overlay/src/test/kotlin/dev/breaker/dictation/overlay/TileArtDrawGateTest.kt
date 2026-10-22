package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The drawing of the mic, read as text: the view and the art paint no call that covers the window, the art takes
 * every colour from a face colour, the look or the colour parameter, every call that passes a colour passes one of
 * those in its colour position, each face of the mic takes the colour its arm names in faceColor, and each picture
 * is drawn into the microphone cell.
 *
 * Each rule runs first on edited copies of the real source (a change that must be reported is made on the real text,
 * with the edit helper proving the text it replaces is there), and then on the real file.
 */
class TileArtDrawGateTest {

    private val view = "TileView.kt"
    private val art = "TileArtDraw.kt"
    private val faces = "TileArtRules.kt"

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** [text] with [old] replaced by [new]; fails by name when [old] is not in [text], so a sample cannot go stale unseen. */
    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    private fun real(name: String): String = ModuleFiles.mainTexts().getValue(name)

    private val fillLine = "private fun fillShape(canvas: Canvas, paint: Paint, shape: ArtShape, color: Int) {"
    private val busyLine = "private fun drawBusy(canvas: Canvas, paint: Paint, look: TileLook, pulseAlpha: Float) {"

    /** A failure here means a call paints the whole window, which the mic never does: the window is translucent and only the art is drawn. */
    @Test
    fun `the view and the art paint no call that covers the whole window`() {
        val viewText = real(view)
        val artText = real(art)
        assertQuiet("the real view", ViewDrawingRules.fillProblems(viewText))
        assertQuiet("the real art", ViewDrawingRules.fillProblems(artText))
        assertFires(
            "a rounded box over the window in the view",
            ViewDrawingRules.fillProblems(edit(viewText, "super.onDraw(canvas)", "super.onDraw(canvas)\ncanvas.drawRoundRect(0f, 0f, width.toFloat(), height.toFloat(), 4f, 4f, paint)")),
            "drawRoundRect",
        )
        assertFires("a colour over the window in the view", ViewDrawingRules.fillProblems(edit(viewText, "super.onDraw(canvas)", "super.onDraw(canvas)\ncanvas.drawColor(look.background)")), "drawColor")
        assertFires("a rectangle in the art", ViewDrawingRules.fillProblems(edit(artText, fillLine, "$fillLine\ncanvas.drawRect(0f, 0f, 1f, 1f, paint)")), "drawRect")
        assertFires("a whole-window call inside a string", ViewDrawingRules.fillProblems("val s = \"canvas.drawColor(1)\"\n"), "drawColor")
        assertQuiet("the paint calls only in comments", ViewDrawingRules.fillProblems("// canvas.drawRoundRect(0f, 0f, 1f, 1f, 4f, 4f, paint)\n" + viewText))
        assertQuiet("a circle and a line are not whole-window calls", ViewDrawingRules.fillProblems("canvas.drawCircle(1f, 1f, 1f, paint)\ncanvas.drawLine(0f, 0f, 1f, 1f, paint)\n"))

        assertEquals("overlay: the view and the art must paint no call that covers the whole window", emptyList<String>(), ViewDrawingRules.fillProblems(viewText) + ViewDrawingRules.fillProblems(artText))
    }

    /**
     * A failure here means a colour reaches the art from outside the face: a hex literal, a Color constant, a shader,
     * a paint alpha or an animator, or a helper is given a colour that no face colour or look field supplies.
     */
    @Test
    fun `the art takes every colour from a face colour or the look and every colour argument is checked`() {
        val artText = real(art)
        assertQuiet("the real art", ViewDrawingRules.artColourProblems(artText))
        assertQuiet("the real calls in the view", ViewDrawingRules.helperCallProblems(real(view)))
        assertFires("a hex literal in a helper", ViewDrawingRules.artColourProblems(edit(artText, fillLine, "$fillLine\nval tint = 0xFF112233.toInt()")), "a hex literal")
        assertFires("a Color constant set as the colour", ViewDrawingRules.artColourProblems(edit(artText, "paint.color = ringDrawColor(look.ring, pulseAlpha)", "paint.color = Color.RED")), "Color.RED")
        assertFires("a hex literal set as the colour", ViewDrawingRules.artColourProblems(edit(artText, "paint.color = color", "paint.color = 0xFF112233.toInt()")), "0xFF112233.toInt()")
        assertFires("a setAlpha call on the paint", ViewDrawingRules.artColourProblems(edit(artText, busyLine, "$busyLine\npaint.setAlpha(128)")), "a paint alpha")
        assertFires("a glow colour made from a number", ViewDrawingRules.artColourProblems(edit(artText, "glowDrawColor(color, pulseAlpha, glowAlpha(layer))", "glowDrawColor(7, pulseAlpha, glowAlpha(layer))")), "glowDrawColor(7")
        assertFires("a bare colour passed to a helper", ViewDrawingRules.artColourProblems(edit(artText, "drawBusy(canvas, paint, look, pulseAlpha)", "drawBusy(canvas, paint, color, pulseAlpha)")), "argument 2")
        assertFires("a number passed as a fill colour", ViewDrawingRules.artColourProblems(edit(artText, "fillShape(canvas, paint, slot.body, slotColor(false, lit, look))", "fillShape(canvas, paint, slot.body, 7)")), "argument 3")
        assertFires("a local colour passed as a fill colour", ViewDrawingRules.artColourProblems(edit(artText, "fillShape(canvas, paint, slot.top, slotColor(true, lit, look))", "fillShape(canvas, paint, slot.top, color)")), "argument 3")
        assertFires("a local named color", ViewDrawingRules.artColourProblems(edit(artText, fillLine, "$fillLine\nval color = 7")), "a local named color")
        assertFires("a helper with a colour and no call check", ViewDrawingRules.artColourProblems(edit(artText, fillLine, "private fun tint(canvas: Canvas, color: Int) {\n}\n$fillLine")), "tint takes a colour")
        assertFires("a glow drawn with a number in the view", ViewDrawingRules.helperCallProblems(edit(real(view), "artDraw.drawGlow(canvas, mic, paint, look.ring, pulseAlpha, density, GlowSpec.TILE, look.ring)", "artDraw.drawGlow(canvas, mic, paint, 7, pulseAlpha, density, GlowSpec.TILE, look.ring)")), "drawGlow")
        assertFires("the art with no colour assignment", ViewDrawingRules.artColourProblems("class A\n"), "no colour assignment")

        assertEquals("overlay: $art must take every colour from a face colour, the look or the colour parameter", emptyList<String>(), ViewDrawingRules.artColourProblems(artText))
    }

    /**
     * A failure here means the colour rule of the view no longer says exactly what the glow of a layer is: the glow
     * colour is accepted only in the form ringDrawColor takes, with its own arguments, and nothing else is loosened.
     */
    @Test
    fun `the view colour rule accepts the glow colour only in its exact form`() {
        val layer = "internal class V {\nfun f() {\npaint.color = glowDrawColor(color, pulseAlpha, glowAlpha(layer))\ndrawRing(canvas, mic, look.ring)\n}\n}\n"
        assertQuiet("the glow colour of a layer", AdapterRules.viewColourProblems(layer))
        assertQuiet("the ring colour form still accepted", AdapterRules.viewColourProblems(edit(layer, "glowDrawColor(color, pulseAlpha, glowAlpha(layer))", "ringDrawColor(color, pulseAlpha)")))
        assertFires("a glow colour with a number", AdapterRules.viewColourProblems(edit(layer, "glowDrawColor(color, pulseAlpha, glowAlpha(layer))", "glowDrawColor(7, pulseAlpha, glowAlpha(layer))")), "glowDrawColor(7, pulseAlpha, glowAlpha(layer))")
        assertFires("a glow colour with a fixed layer alpha", AdapterRules.viewColourProblems(edit(layer, "glowAlpha(layer)", "0.5f")), "glowDrawColor(color, pulseAlpha, 0.5f)")
        assertFires("a glow colour from the look", AdapterRules.viewColourProblems(edit(layer, "glowDrawColor(color,", "glowDrawColor(look.ring,")), "glowDrawColor(look.ring")
    }

    /**
     * A failure here means a part of the mic is drawn from the wrong colour: the body or the well in the outline colour,
     * a body arm in the page colour, a shaded body face shaded from the page colour or by another part's factor, a frame
     * or slot-strip face shaded from the page colour, a face with no arm, or the letters in the outline colour. The body
     * colour is bodyColor(look), so a body face that names look.background is a wrong colour, not a missing one.
     */
    @Test
    fun `each face of the mic takes the colour its arm names in faceColor`() {
        val faceText = real(faces)
        assertQuiet("the real faces", ViewDrawingRules.faceRoleProblems(faceText))
        assertFires("the letters in the outline colour", ViewDrawingRules.faceRoleProblems(edit(faceText, "ArtPart.LETTERS -> look.glyph", "ArtPart.LETTERS -> look.glyphOutline")), "LETTERS")
        assertFires("the body well in the outline colour", ViewDrawingRules.faceRoleProblems(edit(faceText, "ArtPart.CORD_BODY, ArtPart.WELL -> bodyColor(look)", "ArtPart.CORD_BODY, ArtPart.WELL -> look.glyphOutline")), "WELL")
        assertFires("a body arm that returns the page colour", ViewDrawingRules.faceRoleProblems(edit(faceText, "ArtPart.TRIGGER_BODY, ArtPart.HEAD_BODY, ArtPart.CORD_BODY, ArtPart.WELL -> bodyColor(look)", "ArtPart.TRIGGER_BODY, ArtPart.HEAD_BODY, ArtPart.CORD_BODY, ArtPart.WELL -> look.background")), "TRIGGER_BODY")
        assertFires("a shaded body face shaded from the page colour", ViewDrawingRules.faceRoleProblems(edit(faceText, "shade(bodyColor(look), ArtFactors.TRIGGER_FACE)", "shade(look.background, ArtFactors.TRIGGER_FACE)")), "TRIGGER_FACE")
        assertFires("a shaded body face with another part's factor", ViewDrawingRules.faceRoleProblems(edit(faceText, "shade(bodyColor(look), ArtFactors.TRIGGER_FACE)", "shade(bodyColor(look), ArtFactors.TRIGGER_STRIPE)")), "TRIGGER_FACE")
        assertFires("a shaded body face drawn unshaded", ViewDrawingRules.faceRoleProblems(edit(faceText, "ArtPart.TRIGGER_FACE -> shade(bodyColor(look), ArtFactors.TRIGGER_FACE)", "ArtPart.TRIGGER_FACE -> bodyColor(look)")), "TRIGGER_FACE")
        assertFires("a frame face shaded from the background", ViewDrawingRules.faceRoleProblems(edit(faceText, "shade(look.glyphOutline, ArtFactors.FRAME_TOP)", "shade(look.background, ArtFactors.FRAME_TOP)")), "FRAME_TOP")
        assertFires("the slot strip shaded from the background", ViewDrawingRules.faceRoleProblems(edit(faceText, "shade(look.litSegment, ArtFactors.SLOT_TOP)", "shade(look.background, ArtFactors.SLOT_TOP)")), "SLOT_TOP")
        assertFires("a face with no arm at all", ViewDrawingRules.faceRoleProblems(edit(faceText, "ArtPart.LETTERS -> look.glyph\n", "")), "LETTERS")
        assertQuiet("a commented body arm next to the real one", ViewDrawingRules.faceRoleProblems(edit(faceText, "ArtPart.TRIGGER_BODY, ArtPart.HEAD_BODY", "// ArtPart.TRIGGER_BODY -> look.background\nArtPart.TRIGGER_BODY, ArtPart.HEAD_BODY")))

        assertEquals("overlay: $faces must draw each face of the mic from the colour its arm names in faceColor", emptyList<String>(), ViewDrawingRules.faceRoleProblems(faceText))
    }

    /**
     * A failure here means a picture is drawn into a cell other than the microphone cell: the art over the whole window,
     * the glow in the cancel cell, or a picture drawn twice. The microphone cell is cut once as TileLayout.micCell.
     */
    @Test
    fun `the view and the art draw each picture into the microphone cell`() {
        val viewText = real(view)
        assertQuiet("the real view", ViewDrawingRules.artCellProblems(viewText))
        assertFires("the art over the whole window", ViewDrawingRules.artCellProblems(edit(viewText, "artDraw.drawArt(canvas, mic, paint, face, pulseAlpha)", "artDraw.drawArt(canvas, TileRect(0, 0, width, height), paint, face, pulseAlpha)")), "drawArt")
        assertFires("the glow in the cancel cell", ViewDrawingRules.artCellProblems(edit(viewText, "artDraw.drawGlow(canvas, mic,", "artDraw.drawGlow(canvas, TileLayout.cancelCell(s),")), "drawGlow")
        assertFires(
            "the glow drawn twice",
            ViewDrawingRules.artCellProblems(edit(viewText, "artDraw.drawGlow(canvas, mic, paint, look.ring, pulseAlpha, density, GlowSpec.TILE, look.ring)", "artDraw.drawGlow(canvas, mic, paint, look.ring, pulseAlpha, density, GlowSpec.TILE, look.ring)\nartDraw.drawGlow(canvas, mic, paint, look.ring, pulseAlpha, density, GlowSpec.TILE, look.ring)")),
            "drawGlow",
        )
        assertFires("no microphone cell", ViewDrawingRules.artCellProblems(edit(viewText, "val mic = TileLayout.micCell(face.shape, s)\n", "")), "microphone cell")
        assertQuiet("the cell under another name", ViewDrawingRules.artCellProblems(edit(edit(viewText, "val mic =", "val cell ="), "(canvas, mic, paint", "(canvas, cell, paint")))

        assertEquals("overlay: $view must draw the art and the glow into the microphone cell", emptyList<String>(), ViewDrawingRules.artCellProblems(viewText))
    }
}
