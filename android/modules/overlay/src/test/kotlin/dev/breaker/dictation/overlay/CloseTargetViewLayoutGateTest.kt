package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The close target view places its picture with one origin and one scale, and draws its box and its path
 * points from the shape. No JVM test reads the view's layout lines, so these checks read the source text of
 * those lines. Each check is a pure function from source text to a list of problems. Every test runs its
 * check on a sample that breaks it (the problem must be reported), on a sample that keeps it (no problem),
 * and then on the real file. Comments are blanked and string literals are kept.
 */
class CloseTargetViewLayoutGateTest {

    private val viewFile = "CloseTargetView.kt"
    private val sizeLines = listOf(
        "originX = (w - CloseTargetShape.ART_WIDTH * scale) / 2f",
        "originY = (h - CloseTargetShape.ART_HEIGHT * scale) / 2f",
    )
    private val mapXExpression = Regex("fun mapX\\(x: Float\\): Float = ([^\\n]*)")
    private val mapYExpression = Regex("fun mapY\\(y: Float\\): Float = ([^\\n]*)")
    private val movePath = "path.moveTo(mapX(command.x), mapY(command.y))"
    private val linePath = "path.lineTo(mapX(command.x), mapY(command.y))"
    private val curveArguments = listOf(
        "mapX(command.x1),",
        "mapY(command.y1),",
        "mapX(command.x2),",
        "mapY(command.y2),",
        "mapX(command.x),",
        "mapY(command.y),",
    )
    private val boxLines = listOf(
        "val cx = width / 2f",
        "val cy = height / 2f",
        "val left = cx - halfWidth",
        "val top = cy - halfHeight",
        "val right = cx + halfWidth",
        "val bottom = cy + halfHeight",
        "canvas.drawRoundRect(left, top, right, bottom, corner, corner, fillPaint)",
        "canvas.drawRoundRect(left, top, right, bottom, corner, corner, edgePaint)",
    )
    private val halfLines = listOf(
        "val halfWidth = CloseTargetShape.halfWidthPx(scale)",
        "val halfHeight = CloseTargetShape.halfHeightPx(scale)",
        "val corner = CloseTargetShape.cornerPx(scale)",
    )

    /** The size change sets the origin of the picture so that it is centred in the view. */
    private fun originProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "onSizeChanged")
            ?: return listOf("no onSizeChanged function with a block body was found")
        return missingLines(body, sizeLines)
    }

    /** The map functions turn an art point into a pixel: the origin plus the point times the scale. */
    private fun mapProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val mapX = mapXExpression.find(code)?.groupValues?.get(1)?.trim()
        val mapY = mapYExpression.find(code)?.groupValues?.get(1)?.trim()
        return listOfNotNull(
            "mapX is '$mapX', not originX + x * scale".takeIf { mapX != "originX + x * scale" },
            "mapY is '$mapY', not originY + y * scale".takeIf { mapY != "originY + y * scale" },
        )
    }

    /** Every path command maps its points through mapX and mapY, and the cubic points come in x, y order. */
    private fun toPathProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "toPath")
            ?: return listOf("no toPath function with a block body was found")
        val lines = body.lines().map { it.trim() }
        val start = lines.indexOf("path.cubicTo(")
        val args = if (start < 0) emptyList<String>() else lines.drop(start + 1).takeWhile { it != ")" }
        return listOfNotNull(
            "moveTo is missing '$movePath'".takeIf { movePath !in lines },
            "lineTo is missing '$linePath'".takeIf { linePath !in lines },
            "cubicTo arguments are $args, expected $curveArguments".takeIf { args != curveArguments },
        )
    }

    /** The box is drawn from the centre of the view and the half sides, with both drawRoundRect calls. */
    private fun boxProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "onDraw")
            ?: return listOf("no onDraw function with a block body was found")
        return missingLines(body, boxLines)
    }

    /** The half sides and the corner come from the shape at the scale. */
    private fun halfProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "onDraw")
            ?: return listOf("no onDraw function with a block body was found")
        return missingLines(body, halfLines)
    }

    private fun missingLines(body: String, expected: List<String>): List<String> {
        val lines = body.lines().map { it.trim() }
        return expected.filterNot { it in lines }.map { "a line is missing: '$it'" }
    }

    private fun mainText(): String = ModuleFiles.mainTexts().getValue(viewFile)

    /** The text with old replaced by new. It fails by name when old is not in the text, so a sample cannot go stale unseen. */
    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** A failure here means the picture is not centred: one origin line has an operator or an operand changed, or is missing. */
    @Test
    fun `the picture is centred by the origin lines of the size change`() {
        val real = mainText()
        assertFires(
            "the horizontal origin with a plus",
            originProblems(edit(real, "originX = (w - CloseTargetShape.ART_WIDTH * scale) / 2f", "originX = (w + CloseTargetShape.ART_WIDTH * scale) / 2f")),
            "a line is missing",
        )
        assertFires(
            "the vertical origin divided by four",
            originProblems(edit(real, "originY = (h - CloseTargetShape.ART_HEIGHT * scale) / 2f", "originY = (h - CloseTargetShape.ART_HEIGHT * scale) / 4f")),
            "a line is missing",
        )
        assertFires(
            "the horizontal origin line removed",
            originProblems(edit(real, "        originX = (w - CloseTargetShape.ART_WIDTH * scale) / 2f\n", "")),
            "a line is missing",
        )
        assertQuiet(
            "a self-contained size change with both origins",
            originProblems(
                "override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {\n" +
                    "        super.onSizeChanged(w, h, oldw, oldh)\n" +
                    "        scale = CloseTargetShape.scale(w, h)\n" +
                    "        originX = (w - CloseTargetShape.ART_WIDTH * scale) / 2f\n" +
                    "        originY = (h - CloseTargetShape.ART_HEIGHT * scale) / 2f\n" +
                    "        buildPaths()\n" +
                    "    }\n",
            ),
        )

        assertEquals("overlay: $viewFile must centre the picture with the two origin lines", emptyList<String>(), originProblems(real))
    }

    /** A failure here means an art point maps to a pixel with the wrong origin, the wrong operator or the wrong scale. */
    @Test
    fun `the art points map to pixels through the origin and the scale`() {
        val real = mainText()
        assertFires(
            "mapX with a minus",
            mapProblems(edit(real, "originX + x * scale", "originX - x * scale")),
            "mapX is",
        )
        assertFires(
            "mapY with the horizontal origin",
            mapProblems(edit(real, "originY + y * scale", "originX + y * scale")),
            "mapY is",
        )
        assertQuiet(
            "a self-contained pair of map functions",
            mapProblems(
                "private fun mapX(x: Float): Float = originX + x * scale\n" +
                    "\n" +
                    "private fun mapY(y: Float): Float = originY + y * scale\n",
            ),
        )

        assertEquals("overlay: $viewFile must map art points with the origin and the scale", emptyList<String>(), mapProblems(real))
    }

    /** A failure here means a path command maps a point through the wrong function, or the cubic points are out of x, y order. */
    @Test
    fun `every path command maps its points through mapX and mapY and the cubic points stay in x then y order`() {
        val real = mainText()
        assertFires(
            "moveTo with mapY for the x point",
            toPathProblems(edit(real, movePath, "path.moveTo(mapY(command.x), mapY(command.y))")),
            "moveTo is missing",
        )
        assertFires(
            "lineTo with the points swapped",
            toPathProblems(edit(real, linePath, "path.lineTo(mapX(command.y), mapY(command.x))")),
            "lineTo is missing",
        )
        assertFires(
            "the first cubic x point through mapY",
            toPathProblems(edit(real, "mapX(command.x1),", "mapY(command.x1),")),
            "cubicTo arguments are",
        )
        assertFires(
            "the second cubic y point through mapX",
            toPathProblems(edit(real, "mapY(command.y2),", "mapX(command.y2),")),
            "cubicTo arguments are",
        )
        assertQuiet(
            "a self-contained path with the three commands mapped in order",
            toPathProblems(
                "private fun toPath(commands: List<PathCmd>): Path {\n" +
                    "        path.moveTo(mapX(command.x), mapY(command.y))\n" +
                    "        path.lineTo(mapX(command.x), mapY(command.y))\n" +
                    "        path.cubicTo(\n" +
                    "            mapX(command.x1),\n" +
                    "            mapY(command.y1),\n" +
                    "            mapX(command.x2),\n" +
                    "            mapY(command.y2),\n" +
                    "            mapX(command.x),\n" +
                    "            mapY(command.y),\n" +
                    "        )\n" +
                    "    }\n",
            ),
        )

        assertEquals("overlay: $viewFile must map every path point through mapX and mapY in x, y order", emptyList<String>(), toPathProblems(real))
    }

    /** A failure here means the box is drawn from the wrong centre or edge, or a drawRoundRect call is missing or changed. */
    @Test
    fun `the box is drawn from its centre and half sides with both drawRoundRect calls`() {
        val real = mainText()
        assertFires(
            "the left edge with a plus",
            boxProblems(edit(real, "val left = cx - halfWidth", "val left = cx + halfWidth")),
            "val left = cx - halfWidth",
        )
        assertFires(
            "the edge drawRoundRect call with the half width for the corner",
            boxProblems(edit(real, "canvas.drawRoundRect(left, top, right, bottom, corner, corner, edgePaint)", "canvas.drawRoundRect(left, top, right, bottom, corner, halfWidth, edgePaint)")),
            "canvas.drawRoundRect(left, top, right, bottom, corner, corner, edgePaint)",
        )
        assertFires(
            "the bottom edge line removed",
            boxProblems(edit(real, "        val bottom = cy + halfHeight\n", "")),
            "val bottom = cy + halfHeight",
        )
        assertQuiet(
            "a self-contained draw with the centre, the edges and both drawRoundRect calls",
            boxProblems("override fun onDraw(canvas: Canvas) {\n" + boxLines.joinToString(separator = "\n") { "        " + it } + "\n    }\n"),
        )

        assertEquals("overlay: $viewFile must draw the box from its centre, edges and both drawRoundRect calls", emptyList<String>(), boxProblems(real))
    }

    /** A failure here means a half side or the corner comes from the wrong shape function, or is not taken at the scale. */
    @Test
    fun `the half sides and the corner come from the shape at the scale`() {
        val real = mainText()
        assertFires(
            "the half width from the half height",
            halfProblems(edit(real, "CloseTargetShape.halfWidthPx(scale)", "CloseTargetShape.halfHeightPx(scale)")),
            "val halfWidth = CloseTargetShape.halfWidthPx(scale)",
        )
        assertFires(
            "the corner at a fixed scale",
            halfProblems(edit(real, "CloseTargetShape.cornerPx(scale)", "CloseTargetShape.cornerPx(1f)")),
            "val corner = CloseTargetShape.cornerPx(scale)",
        )
        assertFires(
            "the half height line removed",
            halfProblems(edit(real, "        val halfHeight = CloseTargetShape.halfHeightPx(scale)\n", "")),
            "val halfHeight = CloseTargetShape.halfHeightPx(scale)",
        )
        assertQuiet(
            "a self-contained draw with the three shape values",
            halfProblems("override fun onDraw(canvas: Canvas) {\n" + halfLines.joinToString(separator = "\n") { "        " + it } + "\n    }\n"),
        )

        assertEquals("overlay: $viewFile must take the half sides and the corner from the shape at the scale", emptyList<String>(), halfProblems(real))
    }
}
