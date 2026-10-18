package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The close target view is an Android view, so its rules are read from its source text, the way the window
 * adapter rules read their file. Each rule is a pure function from source text to a list of problems.
 * Every test runs its rule on a sample that breaks it (the problem must be reported), on a sample that keeps it
 * (no problem), and then on the real file. Comments are blanked and string literals are kept.
 */
class CloseTargetViewGateTest {

    private val file = "CloseTargetView.kt"
    private val faceFields = listOf("fill", "edge", "edgeActive", "truck", "load", "loadActive")
    private val colourAssignment = Regex("\\.color\\s*=\\s*([^\\n]*)")
    private val plainFace = Regex("face\\.(\\w+)")
    private val branchFace = Regex("if \\(\\w+\\) face\\.(\\w+) else face\\.(\\w+)")
    private val colourSetter = Regex("\\.(setColor|setBackgroundColor|setTint|setColorFilter)\\s*\\(")
    private val colourClass = Regex("\\bColor\\.")
    private val hexLiteral = Regex("\\b0[xX][0-9A-Fa-f]")
    private val alphaAssignment = Regex("(\\w+)\\.alpha\\s*=\\s*([^\\n]*)")
    private val setAlphaCall = Regex("\\bsetAlpha\\s*\\(")
    private val alphaCall = Regex("CloseTargetLook\\.alphaOf\\((\\w+)\\)")
    private val fillTypeWords = Regex("\\b(setFillType|fillType|FillType|EVEN_ODD|INVERSE_WINDING)\\b")
    private val allocation = Regex("\\b(Path|Paint)\\(")
    private val drawOrder = listOf("fillPaint", "edgePaint", "truckPath", "strapsBehindPath", "loadPath", "strapsOverPath")
    private val drawCall = Regex("canvas\\.draw\\w+\\(([^)]*)\\)")
    private val strokeValue = Regex("\\.strokeWidth\\s*=\\s*([^\\n]*)")
    private val strokeNames = listOf("EDGE_IDLE_ART", "EDGE_ACTIVE_ART", "STRAP_ART")
    private val scaleValue = Regex("\\bscale\\s*=(?!=)\\s*([^\\n]*)")
    private val capOther = Regex("Paint\\.Cap\\.(?!BUTT)\\w+")

    /** Rule 1: every paint colour is one field of the face, plainly or chosen by an if between two fields. */
    private fun colourValueOk(value: String): Boolean {
        val plain = plainFace.matchEntire(value)
        if (plain != null) {
            return plain.groupValues[1] in faceFields
        }
        val branch = branchFace.matchEntire(value) ?: return false
        return branch.groupValues[1] in faceFields && branch.groupValues[2] in faceFields
    }

    private fun colourProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val values = colourAssignment.findAll(code).map { it.groupValues[1].trim() }.toList()
        val named = colourSetter.findAll(code).map { it.value }.toList()
        val classes = colourClass.findAll(code).map { it.value }.toList()
        val hexes = hexLiteral.findAll(code).map { it.value }.toList()
        return listOfNotNull("no paint colour is set".takeIf { values.isEmpty() }) +
            values.filterNot { colourValueOk(it) }.map { "a paint colour is set from '$it', not from a field of the face" } +
            (named + classes + hexes).map { "the view names '$it'" }
    }

    /** Rule 2: exactly one alpha is set, on the fill paint, and it is the fill opacity chosen from the two named opacities. */
    private fun alphaProblems(source: String): List<String> {
        val code = SourceText.code(source)
        val assignments = alphaAssignment.findAll(code).toList()
        val setCalls = setAlphaCall.findAll(code).count()
        if (assignments.size != 1) {
            return listOf("expected exactly one alpha assignment, found ${assignments.size}")
        }
        val only = assignments.single()
        val paint = only.groupValues[1]
        val value = only.groupValues[2].trim()
        val opacityName = alphaCall.matchEntire(value)?.groupValues?.get(1)
        val opacityLine = Regex("val $opacityName = ([^\\n]*)").find(code)?.groupValues?.get(1).orEmpty()
        return listOfNotNull(
            "setAlpha is called".takeIf { setCalls > 0 },
            "the alpha is set on '$paint', not on the fill paint".takeIf { paint != "fillPaint" },
            "the alpha is not CloseTargetLook.alphaOf of a named opacity: '$value'".takeIf { opacityName == null },
            "the opacity is not chosen from both fill opacities"
                .takeIf { "CloseTargetLook.FILL_OPACITY_ACTIVE" !in opacityLine || "CloseTargetLook.FILL_OPACITY_IDLE" !in opacityLine },
        )
    }

    /** Rule 3: no fill type is set anywhere, so the default winding makes the holes. */
    private fun fillTypeProblems(source: String): List<String> =
        fillTypeWords.findAll(SourceText.code(source)).map { "the view names the fill type '${it.value}'" }.toList()

    /** Rule 4: onDraw builds no path and no paint. */
    private fun allocationProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "onDraw")
            ?: return listOf("no onDraw function with a block body was found")
        return allocation.findAll(body).map { "onDraw allocates '${it.value}'" }.toList()
    }

    /** Rule 5: the draw calls of onDraw are fill, edge, truck, straps behind, load and straps in front, in that order. */
    private fun orderProblems(source: String): List<String> {
        val body = AdapterRules.blockBodyOf(SourceText.code(source), "onDraw")
            ?: return listOf("no onDraw function with a block body was found")
        val drawn = drawCall.findAll(body).map { call -> drawOrder.filter { it in call.groupValues[1] } }.toList()
        val expected = drawOrder.map { listOf(it) }
        return if (drawn == expected) emptyList() else listOf("the draw calls are $drawn, expected $expected")
    }

    /** Rule 6: every stroke width is an art width (edge or strap) times the scale, and all three art widths are used. */
    private fun strokeProblems(source: String): List<String> {
        val values = strokeValue.findAll(SourceText.code(source)).map { it.groupValues[1].trim() }.toList()
        if (values.isEmpty()) {
            return listOf("no stroke width is set")
        }
        val bad = values.filterNot { it.endsWith("* scale") && strokeNames.any { name -> name in it } }
            .map { "a stroke width is set from '$it', not from an art width times the scale" }
        val missing = strokeNames.filterNot { name -> values.any { name in it } }.map { "no stroke width uses $it" }
        return bad + missing
    }

    /** Rule 7: the scale is assigned exactly once, from CloseTargetShape.scale(w, h). */
    private fun scaleProblems(source: String): List<String> {
        val values = scaleValue.findAll(SourceText.code(source)).map { it.groupValues[1].trim() }.toList()
        if (values.size != 1) {
            return listOf("expected exactly one assignment of scale, found ${values.size}")
        }
        if (values[0] != "CloseTargetShape.scale(w, h)") {
            return listOf("scale is set from '${values[0]}', not from CloseTargetShape.scale(w, h)")
        }
        return emptyList()
    }

    /** Rule 8: the strap paint sets its cap and its join explicitly, and no cap other than butt. */
    private fun capProblems(source: String): List<String> {
        val code = SourceText.code(source)
        return listOfNotNull(
            "the strap paint has no butt cap".takeUnless { "strokeCap = Paint.Cap.BUTT" in code },
            "the strap paint has no miter join".takeUnless { "strokeJoin = Paint.Join.MITER" in code },
            "the view sets a cap other than butt".takeIf { capOther.containsMatchIn(code) },
        )
    }

    private fun mainText(): String = ModuleFiles.mainTexts().getValue(file)

    /** The text with old replaced by new. It fails by name when old is not in the text, so a sample cannot go stale unseen. */
    private fun edit(text: String, old: String, new: String): String {
        check(text.contains(old)) { "overlay: control: the sample has no '$old' to edit, so the control would test nothing" }
        return text.replace(old, new)
    }

    private fun assertFires(what: String, problems: List<String>, part: String) =
        assertTrue("overlay: control: $what must be reported with '$part', got $problems", problems.any { it.contains(part) })

    private fun assertQuiet(what: String, problems: List<String>) =
        assertEquals("overlay: control: $what must not be reported", emptyList<String>(), problems)

    /** A failure here means the view paints a colour that is not a field of its face, so the theme stops deciding the colours. */
    @Test
    fun `the view takes every colour from the face`() {
        val real = mainText()
        assertFires("a Color constant", colourProblems(edit(real, "fillPaint.color = face.fill", "fillPaint.color = Color.RED")), "Color.RED")
        assertFires("a hex literal", colourProblems(edit(real, "truckPaint.color = face.truck", "truckPaint.color = 0xFFE6E9E6.toInt()")), "0xFFE6E9E6")
        assertFires(
            "a colour from outside the face",
            colourProblems(edit(real, "loadPaint.color = if (active) face.loadActive else face.load", "loadPaint.color = palette.danger.argb")),
            "palette.danger.argb",
        )
        assertFires("a setter", colourProblems(edit(real, "strapPaint.color = face.truck", "strapPaint.setColor(face.truck)")), "setColor")
        assertQuiet("the faces' fields and an if between two fields", colourProblems("fillPaint.color = face.fill\nedgePaint.color = if (active) face.edgeActive else face.edge\n"))

        assertEquals("overlay: $file must take every colour from the face", emptyList<String>(), colourProblems(real))
    }

    /** A failure here means the fill is not the only translucent shape, or its opacity is not one of the two named ones. */
    @Test
    fun `the fill alpha comes from the two fill opacities and only the fill is translucent`() {
        val real = mainText()
        assertFires("the alpha on the edge", alphaProblems(edit(real, "fillPaint.alpha = CloseTargetLook", "edgePaint.alpha = CloseTargetLook")), "edgePaint")
        assertFires("a literal alpha", alphaProblems(edit(real, "CloseTargetLook.alphaOf(opacity)", "173")), "not CloseTargetLook.alphaOf")
        assertFires("the idle opacity not named", alphaProblems(edit(real, "else CloseTargetLook.FILL_OPACITY_IDLE", "else 0.5f")), "both fill opacities")
        assertFires("a second alpha", alphaProblems(real + "\nedgePaint.alpha = 255\n"), "exactly one")
        assertFires("setAlpha", alphaProblems(real + "\nedgePaint.setAlpha(255)\n"), "setAlpha")
        assertQuiet("the real file", alphaProblems(real))

        assertEquals("overlay: $file must take the fill alpha from the two named opacities", emptyList<String>(), alphaProblems(real))
    }

    /** A failure here means a fill type is set, which would turn the wheel and window holes into solid shapes. */
    @Test
    fun `no fill type is set, so the default winding makes the holes`() {
        assertFires("the even-odd fill", fillTypeProblems(mainText() + "\nval f = Path.FillType.EVEN_ODD\n"), "EVEN_ODD")
        assertFires("setFillType", fillTypeProblems(mainText() + "\ntruckPath.setFillType(p)\n"), "setFillType")
        assertQuiet("the sample with no fill type", fillTypeProblems("val p = Path()\n"))

        assertEquals("overlay: $file must set no fill type", emptyList<String>(), fillTypeProblems(mainText()))
    }

    /** A failure here means onDraw allocates a path or a paint on every frame. */
    @Test
    fun `onDraw allocates no path and no paint`() {
        val real = mainText()
        assertFires("a path in onDraw", allocationProblems(edit(real, "        val cx = width / 2f", "        val cx = width / 2f\n        val p = Path()")), "Path(")
        assertFires("a paint in onDraw", allocationProblems(edit(real, "        val cx = width / 2f", "        val cx = width / 2f\n        val q = Paint()")), "Paint(")
        assertFires("no onDraw", allocationProblems("class V\n"), "no onDraw")
        assertQuiet("the real onDraw", allocationProblems(real))

        assertEquals("overlay: $file must build no path or paint in onDraw", emptyList<String>(), allocationProblems(real))
    }

    /** A failure here means a layer is drawn out of order: a strap or the load would sit under the wrong shape. */
    @Test
    fun `onDraw draws fill, edge, truck, straps behind, load and straps in front, in that order`() {
        val real = mainText()
        assertFires(
            "the truck before the box",
            orderProblems(edit(real, "        canvas.drawRoundRect(left, top, right, bottom, corner, corner, fillPaint)\n", "        canvas.drawPath(truckPath, truckPaint)\n        canvas.drawRoundRect(left, top, right, bottom, corner, corner, fillPaint)\n")),
            "the draw calls",
        )
        assertFires("the load drawn twice", orderProblems(edit(real, "canvas.drawPath(strapsOverPath, strapPaint)", "canvas.drawPath(loadPath, loadPaint)")), "the draw calls")
        assertFires("no onDraw", orderProblems("class V\n"), "no onDraw")
        assertQuiet("the real onDraw", orderProblems(real))

        assertEquals("overlay: $file must draw the layers in the design order", emptyList<String>(), orderProblems(real))
    }

    /** A failure here means a stroke has a fixed width that does not follow the box, or an art width is missing. */
    @Test
    fun `the stroke widths are the art widths times the scale`() {
        val real = mainText()
        assertFires(
            "a literal strap width",
            strokeProblems(edit(real, "        strapPaint.strokeWidth = CloseTargetArt.STRAP_ART * scale", "        strapPaint.strokeWidth = 3f")),
            "a stroke width is set from '3f'",
        )
        assertFires(
            "a strap width without the scale",
            strokeProblems(edit(real, "CloseTargetArt.STRAP_ART * scale", "CloseTargetArt.STRAP_ART")),
            "a stroke width is set from 'CloseTargetArt.STRAP_ART'",
        )
        assertFires("no strap width", strokeProblems(edit(real, "        strapPaint.strokeWidth = CloseTargetArt.STRAP_ART * scale\n", "")), "no stroke width uses STRAP_ART")
        assertQuiet("the real file", strokeProblems(real))

        assertEquals("overlay: $file stroke widths must be the art widths times the scale", emptyList<String>(), strokeProblems(real))
    }

    /** A failure here means the picture is not drawn at the one uniform scale of the shape. */
    @Test
    fun `the scale comes from CloseTargetShape scale`() {
        val real = mainText()
        assertFires("a literal scale", scaleProblems(edit(real, "        scale = CloseTargetShape.scale(w, h)", "        scale = 1f")), "not from CloseTargetShape.scale")
        assertFires("a scale from another size", scaleProblems(edit(real, "CloseTargetShape.scale(w, h)", "CloseTargetShape.scale(w, w)")), "not from CloseTargetShape.scale(w, h)")
        assertFires("no scale", scaleProblems(edit(real, "        scale = CloseTargetShape.scale(w, h)\n", "")), "found 0")
        assertQuiet("the real file", scaleProblems(real))

        assertEquals("overlay: $file must take its scale from CloseTargetShape.scale", emptyList<String>(), scaleProblems(real))
    }

    /** A failure here means the strap paint has curved or square ends, or does not set its ends at all. */
    @Test
    fun `the strap paint has butt caps and a miter join`() {
        val real = mainText()
        assertFires("a square cap", capProblems(edit(real, "strokeCap = Paint.Cap.BUTT", "strokeCap = Paint.Cap.SQUARE")), "no butt cap")
        assertFires("a bevel join", capProblems(edit(real, "strokeJoin = Paint.Join.MITER", "strokeJoin = Paint.Join.BEVEL")), "no miter join")
        assertFires("a second cap named", capProblems(real + "\nval c = Paint.Cap.SQUARE\n"), "a cap other than butt")
        assertQuiet("the real file", capProblems(real))

        assertEquals("overlay: $file must set butt caps and a miter join on the strap paint", emptyList<String>(), capProblems(real))
    }
}
