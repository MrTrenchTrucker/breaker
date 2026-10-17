package dev.breaker.dictation.overlay

import dev.breaker.shared.tokens.ThemeMode
import dev.breaker.shared.tokens.TruckingTokens
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The path data of the two recording buttons, held three ways: the element lines of the approved svg files (copied
 * character for character, without the indentation), the numbers parsed from those lines, and Kotlin literals. A wrong
 * literal and a wrong svg copy both fail. Ops are the TileArt codes: 0 move, 1 line, 2 cubic, 3 close.
 * The tests read text and numbers only; no test runs the view.
 */
class ControlArtSvgTest {

    private val crossDark = """<path d="M710 371.93 L586.93 495 L710 618.07 L635.07 693 L512 569.93 L388.93 693 L314 618.07 L437.07 495 L314 371.93 L388.93 297 L512 420.07 L635.07 297 Z" fill="#e74c3c" stroke="#ffffff" stroke-width="22" stroke-miterlimit="10"/>"""
    private val crossLight = """<path d="M710 371.93 L586.93 495 L710 618.07 L635.07 693 L512 569.93 L388.93 693 L314 618.07 L437.07 495 L314 371.93 L388.93 297 L512 420.07 L635.07 297 Z" fill="#e74c3c" stroke="#000000" stroke-width="22" stroke-miterlimit="10"/>"""
    private val checkDark = """<path d="M314 518.5 L459.86 664.35 L710 385.69 L643.1 325.65 L456.36 533.79 L377.53 454.96 Z" fill="#34a853" stroke="#ffffff" stroke-width="22" stroke-miterlimit="10"/>"""
    private val checkLight = """<path d="M314 518.5 L459.86 664.35 L710 385.69 L643.1 325.65 L456.36 533.79 L377.53 454.96 Z" fill="#34a853" stroke="#000000" stroke-width="22" stroke-miterlimit="10"/>"""

    private val crossOpsLiteral = listOf(0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 3)
    private val crossXyLiteral = listOf(
        710f, 371.93f, 586.93f, 495f, 710f, 618.07f, 635.07f, 693f,
        512f, 569.93f, 388.93f, 693f, 314f, 618.07f, 437.07f, 495f,
        314f, 371.93f, 388.93f, 297f, 512f, 420.07f, 635.07f, 297f,
    )
    private val checkOpsLiteral = listOf(0, 1, 1, 1, 1, 1, 3)
    private val checkXyLiteral = listOf(
        314f, 518.5f, 459.86f, 664.35f, 710f, 385.69f,
        643.1f, 325.65f, 456.36f, 533.79f, 377.53f, 454.96f,
    )

    /** One svg element read as text: its path ops and numbers, and its fill, stroke, stroke width and miter limit. */
    private class Parsed(
        val ops: List<Int>,
        val numbers: List<Float>,
        val fill: String,
        val stroke: String,
        val width: Float,
        val miter: Float,
    )

    /** The value of attribute [name] in [element], read with a regular expression; fails when the element has none. */
    private fun attribute(element: String, name: String): String =
        Regex("\\s" + name + "=\"([^\"]*)\"").find(element)?.groupValues?.get(1)
            ?: error("overlay: control: the svg element has no " + name + " attribute")

    /** Parses the path data of [element] into ops and numbers in order, and reads its style attributes. */
    private fun parse(element: String): Parsed {
        val tokens = Regex("[MLCZ]|-?[0-9]+(?:\\.[0-9]+)?").findAll(attribute(element, "d")).map { it.value }.toList()
        val ops = ArrayList<Int>()
        val numbers = ArrayList<Float>()
        for (token in tokens) {
            when (token) {
                "M" -> ops.add(TileArt.OP_MOVE)
                "L" -> ops.add(TileArt.OP_LINE)
                "C" -> ops.add(TileArt.OP_CUBIC)
                "Z" -> ops.add(TileArt.OP_CLOSE)
                else -> numbers.add(token.toFloat())
            }
        }
        return Parsed(
            ops,
            numbers,
            attribute(element, "fill"),
            attribute(element, "stroke"),
            attribute(element, "stroke-width").toFloat(),
            attribute(element, "stroke-miterlimit").toFloat(),
        )
    }

    /** True when [actual] is within 0.001 of [expected]. */
    private fun near(expected: Float, actual: Float): Boolean = abs(expected - actual) <= 0.001f

    /** How many of [ops] are [op]. */
    private fun opCount(ops: List<Int>, op: Int): Int = ops.count { it == op }

    /** The box of [numbers] as minimum x, maximum x, minimum y and maximum y; the numbers are x and y in pairs. */
    private fun boxOf(numbers: List<Float>): List<Float> {
        val xs = numbers.filterIndexed { index, _ -> index % 2 == 0 }
        val ys = numbers.filterIndexed { index, _ -> index % 2 == 1 }
        return listOf(
            xs.fold(Float.MAX_VALUE) { low, x -> minOf(low, x) },
            xs.fold(-Float.MAX_VALUE) { high, x -> maxOf(high, x) },
            ys.fold(Float.MAX_VALUE) { low, y -> minOf(low, y) },
            ys.fold(-Float.MAX_VALUE) { high, y -> maxOf(high, y) },
        )
    }

    /** The x of art point [x] once the art box is drawn in [frame] (the canvas transform of the cell). */
    private fun cellX(frame: ArtFrame, x: Float): Float = frame.centreX + (x + frame.originX) * frame.scale

    /**
     * A failure here means the ControlArt path data differs from the numbers of the approved dark svg, or from the
     * Kotlin literals, so a button is drawn in a shape the approved art does not have.
     */
    @Test
    fun `the control art numbers equal the svg text and the literals`() {
        val cross = parse(crossDark)
        val check = parse(checkDark)
        assertEquals("overlay: control: the cross ops differ from the dark svg", cross.ops, ControlArt.cancel.ops.toList())
        assertEquals("overlay: control: the cross numbers differ from the dark svg", cross.numbers, ControlArt.cancel.xy.toList())
        assertEquals("overlay: control: the check ops differ from the dark svg", check.ops, ControlArt.send.ops.toList())
        assertEquals("overlay: control: the check numbers differ from the dark svg", check.numbers, ControlArt.send.xy.toList())
        assertEquals("overlay: control: the cross ops differ from the literals", crossOpsLiteral, ControlArt.cancel.ops.toList())
        assertEquals("overlay: control: the cross numbers differ from the literals", crossXyLiteral, ControlArt.cancel.xy.toList())
        assertEquals("overlay: control: the check ops differ from the literals", checkOpsLiteral, ControlArt.send.ops.toList())
        assertEquals("overlay: control: the check numbers differ from the literals", checkXyLiteral, ControlArt.send.xy.toList())
        assertEquals("overlay: control: the dark cross svg differs from the literals", crossXyLiteral, cross.numbers)
        assertEquals("overlay: control: the dark check svg differs from the literals", checkXyLiteral, check.numbers)
        assertEquals("overlay: control: the cross must have one move", 1, opCount(cross.ops, TileArt.OP_MOVE))
        assertEquals("overlay: control: the cross must have eleven lines", 11, opCount(cross.ops, TileArt.OP_LINE))
        assertEquals("overlay: control: the cross must have one close", 1, opCount(cross.ops, TileArt.OP_CLOSE))
        assertEquals("overlay: control: the cross must have 24 numbers", 24, cross.numbers.size)
        assertEquals("overlay: control: the check must have one move", 1, opCount(check.ops, TileArt.OP_MOVE))
        assertEquals("overlay: control: the check must have five lines", 5, opCount(check.ops, TileArt.OP_LINE))
        assertEquals("overlay: control: the check must have one close", 1, opCount(check.ops, TileArt.OP_CLOSE))
        assertEquals("overlay: control: the check must have 12 numbers", 12, check.numbers.size)
    }

    /**
     * A failure here means the light and the dark svg draw different shapes, or an outline that differs in colour
     * (white on dark, black on light), width (22 units) or miter limit (10) from the values ArtMetrics holds.
     */
    @Test
    fun `the light svg carries the same path and the outline values of the art metrics`() {
        val crossDarkParsed = parse(crossDark)
        val crossLightParsed = parse(crossLight)
        val checkDarkParsed = parse(checkDark)
        val checkLightParsed = parse(checkLight)
        assertEquals("overlay: control: the light cross ops differ from the dark", crossDarkParsed.ops, crossLightParsed.ops)
        assertEquals("overlay: control: the light cross numbers differ from the dark", crossDarkParsed.numbers, crossLightParsed.numbers)
        assertEquals("overlay: control: the light check ops differ from the dark", checkDarkParsed.ops, checkLightParsed.ops)
        assertEquals("overlay: control: the light check numbers differ from the dark", checkDarkParsed.numbers, checkLightParsed.numbers)
        assertEquals("overlay: control: the dark cross stroke is not white", "#ffffff", crossDarkParsed.stroke)
        assertEquals("overlay: control: the light cross stroke is not black", "#000000", crossLightParsed.stroke)
        assertEquals("overlay: control: the dark check stroke is not white", "#ffffff", checkDarkParsed.stroke)
        assertEquals("overlay: control: the light check stroke is not black", "#000000", checkLightParsed.stroke)
        val all = listOf(crossDarkParsed, crossLightParsed, checkDarkParsed, checkLightParsed)
        assertTrue("overlay: control: an svg outline is not 22 units wide", all.all { near(22f, it.width) })
        assertTrue("overlay: control: an svg miter limit is not 10", all.all { near(10f, it.miter) })
        assertTrue("overlay: control: the outline width differs from ArtMetrics.CONTROL_OUTLINE_UNITS", all.all { near(ArtMetrics.CONTROL_OUTLINE_UNITS, it.width) })
        assertTrue("overlay: control: the miter limit differs from ArtMetrics.CONTROL_MITER_LIMIT", all.all { near(ArtMetrics.CONTROL_MITER_LIMIT, it.miter) })
    }

    /**
     * A failure here means a control shape is not one closed contour inside the art box, or its bounding box or corner
     * count differs from the approved shape, so the button is cut, bent or drawn off its place.
     */
    @Test
    fun `each control shape is one closed contour inside the art box with the approved bounds and corners`() {
        val cross = parse(crossDark)
        val check = parse(checkDark)
        assertEquals("overlay: control: the cross must start with a move", TileArt.OP_MOVE, cross.ops.first())
        assertEquals("overlay: control: the cross must end with a close", TileArt.OP_CLOSE, cross.ops.last())
        assertEquals("overlay: control: the check must start with a move", TileArt.OP_MOVE, check.ops.first())
        assertEquals("overlay: control: the check must end with a close", TileArt.OP_CLOSE, check.ops.last())
        assertEquals("overlay: control: the cross must have no cubic", 0, opCount(cross.ops, TileArt.OP_CUBIC))
        assertEquals("overlay: control: the check must have no cubic", 0, opCount(check.ops, TileArt.OP_CUBIC))
        assertTrue("overlay: control: the cross leaves the art box", cross.numbers.all { it >= 0f && it <= TileArt.BOX })
        assertTrue("overlay: control: the check leaves the art box", check.numbers.all { it >= 0f && it <= TileArt.BOX })
        val crossBox = boxOf(cross.numbers)
        val checkBox = boxOf(check.numbers)
        assertTrue(
            "overlay: control: the cross box is not x 314..710 and y 297..693",
            near(314f, crossBox[0]) && near(710f, crossBox[1]) && near(297f, crossBox[2]) && near(693f, crossBox[3]),
        )
        assertTrue(
            "overlay: control: the check box is not x 314..710 and y 325.65..664.35",
            near(314f, checkBox[0]) && near(710f, checkBox[1]) && near(325.65f, checkBox[2]) && near(664.35f, checkBox[3]),
        )
        assertEquals("overlay: control: the cross must have 12 corners", 12, opCount(cross.ops, TileArt.OP_MOVE) + opCount(cross.ops, TileArt.OP_LINE))
        assertEquals("overlay: control: the check must have 6 corners", 6, opCount(check.ops, TileArt.OP_MOVE) + opCount(check.ops, TileArt.OP_LINE))
    }

    /**
     * A failure here means the cross is not drawn in the danger colour, the check not in the primary colour in either
     * theme, or one of the two control parts is listed among the shapes of TileArt.all, which the drawing counts use.
     */
    @Test
    fun `the cross and the check take the danger and primary colours and are not in the art list`() {
        val dark = TileStyle.look(TileState.IDLE, TruckingTokens.palette(ThemeMode.DARK))
        val light = TileStyle.look(TileState.IDLE, TruckingTokens.palette(ThemeMode.LIGHT))
        assertEquals("overlay: control: the dark cross is not the danger colour", dark.danger, faceColor(ArtPart.CANCEL_CROSS, dark))
        assertEquals("overlay: control: the light cross is not the danger colour", light.danger, faceColor(ArtPart.CANCEL_CROSS, light))
        assertEquals("overlay: control: the dark check is not the primary colour", dark.primary, faceColor(ArtPart.SEND_CHECK, dark))
        assertEquals("overlay: control: the light check is not the primary colour", light.primary, faceColor(ArtPart.SEND_CHECK, light))
        assertTrue(
            "overlay: control: a control part is listed in TileArt.all",
            TileArt.all.none { it.part == ArtPart.CANCEL_CROSS || it.part == ArtPart.SEND_CHECK },
        )
    }

    /**
     * A failure here means the art box is not scaled by ART_FILL over the 1024 box in the cell, or the control box is not
     * centred on the cell, so the buttons sit off the cell's middle or at the wrong size.
     */
    @Test
    fun `the control box is 396 art units wide and centred in a square cell of side 200`() {
        val cell = TileRect(0, 0, 200, 200)
        val frame = ArtGeometry.frame(cell)
        assertTrue(
            "overlay: control: the box scale is not 396 times ART_FILL over 1024 for a side of 200",
            near(396f * ArtMetrics.ART_FILL / 1024f * 200f, frame.scale * 396f),
        )
        for (numbers in listOf(parse(crossDark).numbers, parse(checkDark).numbers)) {
            val box = boxOf(numbers)
            assertTrue("overlay: control: a control box is not 396 art units wide", near(396f, box[1] - box[0]))
            val centre = (box[0] + box[1]) / 2f
            assertTrue("overlay: control: a control box is not centred on the cell", near(cell.width / 2f, cellX(frame, centre) - cell.left))
        }
    }
}
