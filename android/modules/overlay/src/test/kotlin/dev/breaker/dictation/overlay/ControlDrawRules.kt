package dev.breaker.dictation.overlay

/**
 * The drawing rules of the two recording buttons, read as text. drawControl fills the shape with the face colour of
 * its part, then strokes the same Path with the trim outline (look.glyphOutline, MITER join, BUTT cap, the control
 * miter limit and the control outline width), inside the art cell, and puts the paint's join, miter and cap back after
 * the passes. controlConstantProblems reads the two constants and the two face arms the buttons are drawn with.
 *
 * Each rule is a pure function from source text to a list of problems. Comments are blanked by [SourceText.code], and
 * a construct that cannot be found is a problem, never a pass.
 */
internal object ControlDrawRules {

    private val stylePattern = Regex("""paint\.style\s*=\s*Paint\.Style\.(\w+)""")
    private val colourPattern = Regex("""paint\.color\s*=\s*([^\n]*)""")
    private val drawPattern = Regex("""canvas\.drawPath\(""")
    private val namedDrawPattern = Regex("""canvas\.drawPath\(\s*(\w+)\s*,\s*paint\s*\)""")
    private val inCellOpen = Regex("""inCell\(\s*canvas\s*,\s*cell\s*\)\s*\{""")
    private val readJoin = Regex("""val\s+join\s*=\s*paint\.strokeJoin\b""")
    private val readMiter = Regex("""val\s+miter\s*=\s*paint\.strokeMiter\b""")
    private val readCap = Regex("""val\s+cap\s*=\s*paint\.strokeCap\b""")
    private val restoreJoin = Regex("""paint\.strokeJoin\s*=\s*join\b""")
    private val restoreMiter = Regex("""paint\.strokeMiter\s*=\s*miter\b""")
    private val restoreCap = Regex("""paint\.strokeCap\s*=\s*cap\b""")

    /** The last match of [pattern] in [body] that starts before [index], or null. */
    private fun lastBefore(pattern: Regex, body: String, index: Int): MatchResult? =
        pattern.findAll(body).filter { it.range.first < index }.lastOrNull()

    /** The start offset of the first match of [pattern] in [body], or null. */
    private fun firstAt(pattern: Regex, body: String): Int? = pattern.find(body)?.range?.first

    /** The value of the last paint.[property] assignment before [index], as written, or null. */
    private fun valueBefore(body: String, property: String, index: Int): String? =
        lastBefore(Regex("""paint\.$property\s*=\s*([\w.]+)"""), body, index)?.groupValues?.get(1)

    /** The style named by the last paint.style assignment before [index], or null. */
    private fun styleBefore(body: String, index: Int): String? =
        lastBefore(stylePattern, body, index)?.groupValues?.get(1)

    /** The text of the last paint.color assignment before [index], trimmed, or null. */
    private fun colourBefore(body: String, index: Int): String? =
        lastBefore(colourPattern, body, index)?.groupValues?.get(1)?.trim()

    /** True when a match of [pattern] stands after [after] in [body], and [after] is known. */
    private fun restoredAfter(body: String, pattern: Regex, after: Int?): Boolean =
        after != null && pattern.findAll(body).any { it.range.first > after }

    /**
     * The problems of drawControl in [source]: the first pass is the fill (FILL style, faceColor of the part); the second
     * is the outline (STROKE style, look.glyphOutline, MITER join, BUTT cap, the control miter limit and outline width),
     * on the same Path; the join, miter and cap are read before the first pass and put back after the inCell block;
     * and both passes sit inside inCell(canvas, cell).
     */
    fun drawControlProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "drawControl")
            ?: return listOf("TileArtDraw has no drawControl function with a block body")
        val draws = drawPattern.findAll(body).map { it.range.first }.toList()
        if (draws.size != 2) {
            return listOf("drawControl does not draw exactly two passes with canvas.drawPath, the fill and then the outline")
        }
        val fill = draws[0]
        val outline = draws[1]
        val names = namedDrawPattern.findAll(body).map { it.groupValues[1] }.toList()
        val inCell = inCellOpen.find(body)
        val inCellClose = inCell?.let { SourceText.closeOf(body, it.range.last) }
        return listOfNotNull(
            "the first drawPath is not preceded by the FILL style".takeUnless { styleBefore(body, fill) == "FILL" },
            "the fill colour is not faceColor(shape.part, look)".takeUnless { colourBefore(body, fill) == "faceColor(shape.part, look)" },
            "the outline drawPath is not preceded by the STROKE style".takeUnless { styleBefore(body, outline) == "STROKE" },
            "the outline colour is not look.glyphOutline".takeUnless { colourBefore(body, outline) == "look.glyphOutline" },
            "the fill and the outline are not drawn on the same path".takeUnless { names.size == 2 && names[0] == names[1] },
            "the outline join is not Paint.Join.MITER before its drawPath".takeUnless { valueBefore(body, "strokeJoin", outline) == "Paint.Join.MITER" },
            "the outline cap is not Paint.Cap.BUTT before its drawPath".takeUnless { valueBefore(body, "strokeCap", outline) == "Paint.Cap.BUTT" },
            "the outline miter limit is not ArtMetrics.CONTROL_MITER_LIMIT before its drawPath".takeUnless {
                valueBefore(body, "strokeMiter", outline) == "ArtMetrics.CONTROL_MITER_LIMIT"
            },
            "the outline width is not ArtMetrics.CONTROL_OUTLINE_UNITS before its drawPath".takeUnless {
                valueBefore(body, "strokeWidth", outline) == "ArtMetrics.CONTROL_OUTLINE_UNITS"
            },
            "drawControl does not save the paint join before the first pass".takeUnless { (firstAt(readJoin, body) ?: Int.MAX_VALUE) < fill },
            "drawControl does not save the paint miter limit before the first pass".takeUnless { (firstAt(readMiter, body) ?: Int.MAX_VALUE) < fill },
            "drawControl does not save the paint cap before the first pass".takeUnless { (firstAt(readCap, body) ?: Int.MAX_VALUE) < fill },
            "drawControl does not restore the paint join after the inCell block".takeUnless { restoredAfter(body, restoreJoin, inCellClose) },
            "drawControl does not restore the paint miter limit after the inCell block".takeUnless { restoredAfter(body, restoreMiter, inCellClose) },
            "drawControl does not restore the paint cap after the inCell block".takeUnless { restoredAfter(body, restoreCap, inCellClose) },
            "drawControl does not draw the fill and the outline inside inCell(canvas, cell)".takeUnless {
                inCell != null && inCell.range.first < fill && inCellClose != null && inCellClose > outline
            },
        )
    }

    /**
     * The problems of the constants and the face arms of the buttons in [source]: the outline width is 22f, the miter
     * limit is 10f, and faceColor paints the cross in look.danger and the check in look.primary.
     */
    fun controlConstantProblems(source: String): List<String> {
        val code = SourceText.code(source)
        return listOfNotNull(
            "CONTROL_OUTLINE_UNITS is not 22f".takeUnless { Regex("""CONTROL_OUTLINE_UNITS\s*:\s*Float\s*=\s*22f\b""").containsMatchIn(code) },
            "CONTROL_MITER_LIMIT is not 10f".takeUnless { Regex("""CONTROL_MITER_LIMIT\s*:\s*Float\s*=\s*10f\b""").containsMatchIn(code) },
            "the CANCEL_CROSS arm of faceColor is not look.danger".takeUnless { Regex("""ArtPart\.CANCEL_CROSS\s*->\s*look\.danger\b""").containsMatchIn(code) },
            "the SEND_CHECK arm of faceColor is not look.primary".takeUnless { Regex("""ArtPart\.SEND_CHECK\s*->\s*look\.primary\b""").containsMatchIn(code) },
        )
    }
}
