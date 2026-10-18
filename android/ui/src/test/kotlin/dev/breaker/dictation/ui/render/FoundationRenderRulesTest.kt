package dev.breaker.dictation.ui.render

import dev.breaker.dictation.ui.gate.mainSourceOf
import dev.breaker.dictation.ui.gate.withoutComments
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Source-text rules for the renderer's foundation. Each rule reads one function of a file, with the
 * comments taken out and the spacing of the text collapsed, and reports what the function lacks.
 * Each rule is quiet on the real text and fires on samples made from the real text, including a
 * sample that comments out the pinned line, and a sample for each place a line is written twice.
 */
class FoundationRenderRulesTest {
    private val renderer = mainSourceOf("render/ScreenRenderer.kt")
    private val nodeViews = mainSourceOf("render/NodeViews.kt")
    private val textRow = mainSourceOf("render/TextRowView.kt")
    private val cards = mainSourceOf("render/BoxLook.kt")

    private val whitespace = Regex("""\s+""")

    private val cardFill =
        "if (node.outline == null && node.edge == null) { setBackgroundColor(theme.argbOf(node.background)) } else { background = cardOf(node, theme) }"

    private val wrapKinds =
        "private fun wrapsInRow(node: Node): Boolean = node is MenuIcon || node is Switch || node is BackArrow || node is TagPill || node is Rule || node is Dot || node is TouchGap || (node is Action && node.kit == KitLook.TEXT)"

    /** The text from [start] up to [end], or to the end of [text] when [end] is null; empty when [start] is missing. */
    private fun between(text: String, start: String, end: String?): String {
        val after = text.substringAfter(start, "")
        return if (end == null) after else after.substringBefore(end, after)
    }

    /** [text] with its comments taken out, then every run of whitespace, line breaks included, made into one space. */
    private fun flat(text: String): String = withoutComments(text).replace(whitespace, " ")

    /** How many times [part] occurs in [text]. */
    private fun occurrences(text: String, part: String): Int = text.split(part).size - 1

    /** [text] with the second occurrence of [part] replaced by [with]. */
    private fun replaceSecond(text: String, part: String, with: String): String {
        val first = text.indexOf(part)
        val second = text.indexOf(part, first + part.length)
        return text.substring(0, second) + with + text.substring(second + part.length)
    }

    private fun boxProblems(text: String): List<String> {
        val body = flat(between(text, "private fun boxOf(", "private fun labelOf("))
        return listOfNotNull(
            "boxOf draws the card fill only when an outline or an edge bar is set".takeUnless { cardFill in body },
        )
    }

    private fun labelProblems(text: String): List<String> {
        val body = flat(between(text, "private fun labelOf(", "private fun actionOf("))
        return listOfNotNull(
            "labelOf draws the caps through displayText".takeUnless { "text = displayText(node.text, node.caps)" in body },
            "labelOf asks for the italic face of its role".takeUnless { "typeface = typefaceOf(node.role, theme, node.italic)" in body },
        )
    }

    private fun wrapProblems(text: String): List<String> {
        val body = flat(between(text, "private fun childOf(", "private fun linkToggle("))
        return listOfNotNull(
            "a horizontal box gives the wrapping kinds their own width".takeUnless {
                "if (wrapsInRow(node)) wrapParams() else LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)" in body
            },
            "the wrapping kinds are the named ones and no others".takeUnless { wrapKinds in body },
        )
    }

    private fun menuProblems(text: String): List<String> {
        val body = flat(between(text, "private fun menuIconOf(", "private fun switchOf("))
        return listOfNotNull(
            "the menu lines are stacked in a vertical holder".takeUnless {
                "orientation = LinearLayout.VERTICAL" in body && "orientation = LinearLayout.HORIZONTAL" !in body
            },
            "the stacked menu lines keep a gap between them".takeUnless { "if (index > 0) topMargin =" in body },
        )
    }

    private fun kitProblems(text: String): List<String> {
        val body = flat(between(text, "private fun kitActionOf(", "private fun alignmentOf("))
        return listOfNotNull(
            "the text look draws the text row".takeUnless { "KitLook.TEXT -> TextRowView(context, node.text, theme)" in body },
        )
    }

    private fun backArrowProblems(text: String): List<String> {
        val body = flat(between(text, "private fun backArrowOf(", "/** The colour of [slot]"))
        return listOfNotNull(
            "the back arrow hands its own intent to its view".takeUnless {
                "BackArrowView( context, node.intent, theme.argbOf(PaletteSlot.TEXT)," in body && "onIntent, ).apply { layoutParams = wrapParams(); contentDescription = node.description }" in body
            },
        )
    }

    private fun backArrowViewProblems(text: String): List<String> {
        val body = flat(between(text, "internal class BackArrowView(", null))
        return listOfNotNull(
            "the back arrow reports its intent on a click".takeUnless { "setOnClickListener { report(intent) }" in body },
            "the back arrow draws a chevron".takeUnless { "drawPath(chevron, paint)" in body },
        )
    }

    private fun textRowProblems(text: String): List<String> {
        val body = flat(text)
        return listOfNotNull(
            "the row is as tall as the touch floor".takeUnless { "touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density)" in body },
            "the hairline is drawn in the trim slot".takeUnless { "theme.color(PaletteSlot.TRIM).argb" in body },
            "the hairline starts at the drawer divider".takeUnless { "HomeGeometry.DIVIDER_X0_DP * density" in body },
            "the hairline ends at the drawer divider".takeUnless { "HomeGeometry.DIVIDER_X1_DP * density" in body },
            "the row draws its capitals through drawnLabel at the measure and at the draw".takeUnless {
                occurrences(body, "drawnLabel(label)") >= 2
            },
        )
    }

    private fun textFaceProblems(text: String): List<String> {
        val body = flat(text)
        return listOfNotNull(
            "the row takes the display face at the measure and at the draw".takeUnless {
                occurrences(body, "typefaceOf(TypeRole.DISPLAY, theme)") == 2
            },
            "the row takes the display size at the measure and at the draw".takeUnless {
                occurrences(body, "roleTextSizePx(context, TypeRole.DISPLAY) / density") == 2
            },
        )
    }

    private fun cardStrokeProblems(text: String): List<String> {
        val body = flat(text)
        return listOfNotNull(
            "the card stroke is drawn inside the bounds".takeUnless {
                "canvas.drawRect(left + halfStroke, top + halfStroke, right - halfStroke, bottom - halfStroke, paint)" in body
            },
        )
    }

    private fun cardAlphaProblems(text: String): List<String> {
        val body = flat(text)
        return listOfNotNull(
            "the card paints each colour at the drawable alpha".takeUnless { "paint.color = scaledAlpha(colour, drawAlpha)" in body },
            "the card takes the alpha it is given".takeUnless { "drawAlpha = alpha" in body },
        )
    }

    private fun assertFires(what: String, problems: List<String>) =
        assertTrue("render: foundation: $what must be reported, got $problems", problems.isNotEmpty())

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("render: foundation: $what must not be reported", emptyList<String>(), problems)

    @Test
    fun `the box draws its card fill only when an outline or an edge bar is set`() {
        assertQuiet("the real renderer", boxProblems(renderer))
        assertFires("a card fill joined with or", boxProblems(renderer.replace("node.outline == null && node.edge == null", "node.outline == null || node.edge == null")))
        assertFires("the card fill removed", boxProblems(renderer.replace("background = cardOf(node, theme)", "background = null")))
    }

    @Test
    fun `the label draws its caps and its italic face through the helper and the role`() {
        assertQuiet("the real renderer", labelProblems(renderer))
        assertFires("the caps not drawn", labelProblems(renderer.replace("text = displayText(node.text, node.caps)", "text = node.text")))
        assertFires("the italic not drawn", labelProblems(renderer.replace("typefaceOf(node.role, theme, node.italic)", "typefaceOf(node.role, theme, false)")))
        assertFires("the caps line commented out", labelProblems(renderer.replace("text = displayText(node.text, node.caps)", "// text = displayText(node.text, node.caps)")))
    }

    @Test
    fun `a horizontal box gives exactly the named kinds their own width`() {
        assertQuiet("the real renderer", wrapProblems(renderer))
        assertFires("the rule dropped from the wrap set", wrapProblems(renderer.replace("|| node is Rule ||", "||")))
        assertFires("a box added to the wrap set", wrapProblems(renderer.replace("node is Rule ||", "node is Rule || node is Box ||")))
    }

    @Test
    fun `the menu lines stack vertically with a gap, in one holder`() {
        val menu = between(renderer, "private fun menuIconOf(", "private fun switchOf(")
        assertQuiet("the real renderer", menuProblems(renderer))
        assertFires("the holder back to a row", menuProblems(renderer.replace(menu, menu.replace("orientation = LinearLayout.VERTICAL", "orientation = LinearLayout.HORIZONTAL"))))
        assertFires("the gap removed", menuProblems(renderer.replace("if (index > 0) topMargin =", "if (index > 9) topMargin =")))
    }

    @Test
    fun `the text look is drawn by the text row in the kit branch`() {
        assertQuiet("the real renderer", kitProblems(renderer))
        assertFires("the text look drawn as a raised face", kitProblems(renderer.replace("KitLook.TEXT -> TextRowView(context, node.text, theme)", "KitLook.TEXT -> Button3dView(context, node.text, theme)")))
        assertFires("the text look dropped", kitProblems(renderer.replace("KitLook.TEXT -> TextRowView(context, node.text, theme)", "")))
        assertFires("the text look commented out", kitProblems(renderer.replace("KitLook.TEXT -> TextRowView(context, node.text, theme)", "// KitLook.TEXT -> TextRowView(context, node.text, theme)")))
    }

    @Test
    fun `the back arrow gets its own intent and reports it`() {
        assertQuiet("the real renderer", backArrowProblems(renderer))
        assertFires("the back arrow given another intent", backArrowProblems(renderer.replace("node.intent,", "ScreenIntent.ToggleTheme,")))
        assertFires("the back arrow drawn in another slot", backArrowProblems(renderer.replace("theme.argbOf(PaletteSlot.TEXT),", "theme.argbOf(PaletteSlot.TRIM),")))
        assertQuiet("the real back arrow view", backArrowViewProblems(nodeViews))
        assertFires("the back arrow with no click", backArrowViewProblems(nodeViews.replace("setOnClickListener { report(intent) }", "setOnClickListener { }")))
        assertFires("the back arrow with no chevron", backArrowViewProblems(nodeViews.replace("drawPath(chevron, paint)", "canvas.drawRect(0f, 0f, 0f, 0f, paint)")))
    }

    @Test
    fun `the text row is as tall as the touch floor, hairlined in the trim slot and capitalised`() {
        assertQuiet("the real text row", textRowProblems(textRow))
        assertFires("the row shorter than the floor", textRowProblems(textRow.replace("touchTargetPx(ControlsFactors.TOUCH_MIN_DP, density)", "touchTargetPx(ControlsFactors.CORNER_DP, density)")))
        assertFires("the hairline in the text slot", textRowProblems(textRow.replace("theme.color(PaletteSlot.TRIM).argb", "theme.color(PaletteSlot.TEXT).argb")))
        assertFires("the words drawn at the measure only, not at the draw", textRowProblems(replaceSecond(textRow, "drawnLabel(label)", "label")))
        assertFires("the words drawn at the draw only, not at the measure", textRowProblems(textRow.replaceFirst("drawnLabel(label)", "label")))
        assertFires("the words not drawn through the helper", textRowProblems(textRow.replace("drawnLabel(label)", "label")))
    }

    @Test
    fun `the text row hairline runs from the drawer divider start to the divider end`() {
        assertQuiet("the real text row", textRowProblems(textRow))
        assertFires("the hairline starting at the edge", textRowProblems(textRow.replace("HomeGeometry.DIVIDER_X0_DP * density", "0f")))
        assertFires("the hairline running to the row end", textRowProblems(textRow.replace("HomeGeometry.DIVIDER_X1_DP * density", "width.toFloat()")))
    }

    @Test
    fun `the text row takes its face and size from the display role at both sites`() {
        assertQuiet("the real text row", textFaceProblems(textRow))
        assertFires("the default face", textFaceProblems(textRow.replace("typefaceOf(TypeRole.DISPLAY, theme)", "Typeface.DEFAULT")))
        assertFires("the face at the measure only", textFaceProblems(textRow.replaceFirst("typefaceOf(TypeRole.DISPLAY, theme)", "typefaceOf(TypeRole.BODY, theme)")))
        assertFires("the size at the draw only", textFaceProblems(replaceSecond(textRow, "roleTextSizePx(context, TypeRole.DISPLAY) / density", "ControlsGeometry.LABEL_CAP_DP")))
        assertFires("the size at the measure only", textFaceProblems(textRow.replaceFirst("roleTextSizePx(context, TypeRole.DISPLAY) / density", "ControlsGeometry.LABEL_CAP_DP")))
        assertFires("a third face call stands in for a site", textFaceProblems(textRow + "\nval stray = typefaceOf(TypeRole.DISPLAY, theme)\n"))
        assertFires("a face call removed from the draw", textFaceProblems(replaceSecond(textRow, "typefaceOf(TypeRole.DISPLAY, theme)", "")))
        assertFires("a third size call stands in for a site", textFaceProblems(textRow + "\nval stray = roleTextSizePx(context, TypeRole.DISPLAY) / density\n"))
        assertFires("a size call removed from the draw", textFaceProblems(replaceSecond(textRow, "roleTextSizePx(context, TypeRole.DISPLAY) / density", "")))
        assertQuiet("a comment naming both calls", textFaceProblems(textRow + "\n// typefaceOf(TypeRole.DISPLAY, theme) and roleTextSizePx(context, TypeRole.DISPLAY) / density\n"))
    }

    @Test
    fun `the card stroke sits inside the bounds of the box`() {
        assertQuiet("the real card drawable", cardStrokeProblems(cards))
        assertFires("the stroke on the edge", cardStrokeProblems(cards.replace("left + halfStroke, top + halfStroke", "left, top")))
        assertFires("the stroke drawn from the outer bounds", cardStrokeProblems(cards.replace("right - halfStroke, bottom - halfStroke", "right, bottom")))
    }

    @Test
    fun `the card draws each colour at the drawable alpha, so setAlpha changes what is drawn`() {
        assertQuiet("the real card drawable", cardAlphaProblems(cards))
        assertFires("the colour drawn without the alpha", cardAlphaProblems(cards.replace("paint.color = scaledAlpha(colour, drawAlpha)", "paint.color = colour")))
        assertFires("the alpha not taken", cardAlphaProblems(cards.replace("drawAlpha = alpha", "drawAlpha = 255")))
    }

    private val descriptionFromId = Regex("""contentDescription\s*=.*\bid\b""")
    private val switchNamedById = Regex("""SwitchView\(\s*context,\s*node\.id\b""")

    /** The problems in [text] with a content description read from a node id, or a switch named by one. */
    private fun descriptionProblems(text: String): List<String> {
        val lines = withoutComments(text).lines()
        return listOfNotNull(
            "a content description built from a node id".takeIf { lines.any { descriptionFromId.containsMatchIn(it) } },
            "the switch named by its node id".takeIf { switchNamedById.containsMatchIn(flat(text)) },
        )
    }

    @Test
    fun `no content description is built from a node id`() {
        assertQuiet("the real renderer", descriptionProblems(renderer))
        assertQuiet("the real node views", descriptionProblems(nodeViews))
        assertFires("a description set from the id", descriptionProblems("menu.contentDescription = node.id\n"))
        assertFires("the switch named by its id", descriptionProblems("return SwitchView(context, node.id, 1, 2, theme)\n"))
        assertQuiet("a description from the node's own words", descriptionProblems("menu.contentDescription = node.description\n"))
    }
}
