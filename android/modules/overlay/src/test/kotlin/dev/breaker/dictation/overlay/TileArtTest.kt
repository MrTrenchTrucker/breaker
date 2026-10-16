package dev.breaker.dictation.overlay

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The microphone art as numeric data: every shape matches its svg line in op count, number count and
 * number sum, the totals hold, every shape is closed and inside the box, and the silhouette, the slots and
 * the lettering have their expected shape.
 */
class TileArtTest {

    /** One row per svg line 19 to 47: the part, the number of ops, the number of numbers and their sum. */
    private data class Row(val part: ArtPart, val ops: Int, val numbers: Int, val sum: Double)

    private val rows = listOf(
        Row(ArtPart.TRIGGER_BODY, 10, 34, 8668.000),
        Row(ArtPart.TRIGGER_FACE, 10, 34, 8458.000),
        Row(ArtPart.TRIGGER_STRIPE, 5, 8, 2128.000),
        Row(ArtPart.HEAD_BODY, 14, 42, 19817.006),
        Row(ArtPart.CORD_BODY, 5, 8, 5660.000),
        Row(ArtPart.LOWER_LEFT, 5, 8, 3819.000),
        Row(ArtPart.LOWER_RIGHT, 5, 8, 5557.000),
        Row(ArtPart.FRAME_TOP, 5, 8, 2416.000),
        Row(ArtPart.FRAME_LEFT, 5, 8, 2280.000),
        Row(ArtPart.FRAME_BOTTOM, 5, 8, 3776.000),
        Row(ArtPart.FRAME_RIGHT, 5, 8, 3912.000),
        Row(ArtPart.WELL, 5, 8, 3096.000),
        Row(ArtPart.SLOT, 5, 8, 2471.060),
        Row(ArtPart.SLOT_TOP, 5, 8, 1903.060),
        Row(ArtPart.SLOT, 5, 8, 2721.060),
        Row(ArtPart.SLOT_TOP, 5, 8, 2153.060),
        Row(ArtPart.SLOT, 5, 8, 2971.020),
        Row(ArtPart.SLOT_TOP, 5, 8, 2403.020),
        Row(ArtPart.SLOT, 5, 8, 3220.980),
        Row(ArtPart.SLOT_TOP, 5, 8, 2652.980),
        Row(ArtPart.SLOT, 5, 8, 3470.940),
        Row(ArtPart.SLOT_TOP, 5, 8, 2902.940),
        Row(ArtPart.SLOT, 5, 8, 3720.940),
        Row(ArtPart.SLOT_TOP, 5, 8, 3152.940),
        Row(ArtPart.LEDGE_EDGE, 5, 8, 3852.000),
        Row(ArtPart.LEDGE_FACE, 5, 8, 4012.000),
        Row(ArtPart.LEDGE_END_LEFT, 4, 6, 2404.000),
        Row(ArtPart.LEDGE_END_RIGHT, 4, 6, 3556.000),
        Row(ArtPart.LETTERS, 137, 438, 248412.272),
    )

    private fun numberSum(shape: ArtShape): Double = shape.xy.fold(0.0) { acc, v -> acc + v }

    private fun opNumbers(op: Int): Int = when (op) {
        TileArt.OP_MOVE, TileArt.OP_LINE -> 2
        TileArt.OP_CUBIC -> 6
        else -> 0
    }

    private fun coords(shape: ArtShape, axis: Int): List<Float> = (axis until shape.xy.size step 2).map { shape.xy[it] }

    private fun lowest(shape: ArtShape, axis: Int): Float = coords(shape, axis).minOrNull()!!

    private fun highest(shape: ArtShape, axis: Int): Float = coords(shape, axis).maxOrNull()!!

    /** A failure means a shape has a different op count, number count or number sum from its svg line. */
    @Test
    fun `each of the 29 shapes has the op count, number count and number sum of its svg line`() {
        assertEquals("overlay: the art expected 29 shapes", 29, TileArt.all.size)
        rows.forEachIndexed { i, row ->
            val shape = TileArt.all[i]
            assertEquals("overlay: shape $i expected part ${row.part}", row.part, shape.part)
            assertEquals("overlay: ${row.part} expected ${row.ops} ops", row.ops, shape.ops.size)
            assertEquals("overlay: ${row.part} expected ${row.numbers} numbers", row.numbers, shape.xy.size)
            assertTrue("overlay: ${row.part} expected a number sum of ${row.sum}", abs(numberSum(shape) - row.sum) <= 0.05)
        }
    }

    /** A failure means the art has other totals than the svg table: 40 moves, 155 lines, 59 cubics, 40 closes and 744 numbers. */
    @Test
    fun `the art holds 40 moves, 155 lines, 59 cubics and 40 closes in 744 numbers`() {
        val ops = TileArt.all.flatMap { it.ops.toList() }
        assertEquals("overlay: moves expected 40", 40, ops.count { it == TileArt.OP_MOVE })
        assertEquals("overlay: lines expected 155", 155, ops.count { it == TileArt.OP_LINE })
        assertEquals("overlay: cubics expected 59", 59, ops.count { it == TileArt.OP_CUBIC })
        assertEquals("overlay: closes expected 40", 40, ops.count { it == TileArt.OP_CLOSE })
        assertEquals("overlay: numbers expected 744", 744, TileArt.all.sumOf { it.xy.size })
    }

    /** A failure means a shape holds other numbers than its ops need, or a contour is not closed before the next move. */
    @Test
    fun `every shape holds the numbers its ops need and closes each contour before the next move`() {
        for (shape in TileArt.all) {
            assertEquals("overlay: ${shape.part} expected numbers for its ops", shape.ops.sumOf { opNumbers(it) }, shape.xy.size)
            assertEquals("overlay: ${shape.part} expected to end with a close", TileArt.OP_CLOSE, shape.ops.last())
            for (i in 1 until shape.ops.size) {
                if (shape.ops[i] == TileArt.OP_MOVE) {
                    assertEquals("overlay: ${shape.part} expected a close before the move at op $i", TileArt.OP_CLOSE, shape.ops[i - 1])
                }
            }
        }
    }

    /** A failure means a coordinate lies outside the 1024 box, so a shape would be drawn beyond its cell. */
    @Test
    fun `every coordinate of every shape lies inside the 1024 box`() {
        for (shape in TileArt.all) {
            for (v in shape.xy) {
                assertTrue("overlay: ${shape.part} coordinate $v expected from 0 to ${TileArt.BOX}", v >= 0f && v <= TileArt.BOX)
            }
        }
    }

    /** A failure means the outline is drawn around other shapes, or in another order. */
    @Test
    fun `the silhouette is the trigger body, the head body and the cord body in that order`() {
        assertEquals(
            "overlay: the silhouette expected the trigger body, head body and cord body",
            listOf(ArtPart.TRIGGER_BODY, ArtPart.HEAD_BODY, ArtPart.CORD_BODY),
            TileArt.silhouette.map { it.part },
        )
    }

    /** A failure means a slot is out of step, has another width, lies outside the well, or has a strip off its x range. */
    @Test
    fun `the six slots step right at one width inside the well and each strip sits over its slot`() {
        val slots = TileArt.slots
        assertEquals("overlay: the art expected 6 slots", 6, slots.size)
        for (i in 1 until slots.size) {
            assertTrue(
                "overlay: slot $i expected its left edge right of slot ${i - 1}",
                lowest(slots[i].body, 0) > lowest(slots[i - 1].body, 0),
            )
        }
        val width = highest(slots[0].body, 0) - lowest(slots[0].body, 0)
        for (slot in slots) {
            val body = slot.body
            assertTrue(
                "overlay: a slot expected the width of the first slot, within 0.01",
                abs(highest(body, 0) - lowest(body, 0) - width) <= 0.01f,
            )
            assertTrue(
                "overlay: a slot expected to lie inside the well from 316 to 708 by 100 to 424",
                lowest(body, 0) >= 316f && highest(body, 0) <= 708f && lowest(body, 1) >= 100f && highest(body, 1) <= 424f,
            )
            assertTrue(
                "overlay: a slot strip expected the x range of its slot",
                abs(lowest(slot.top, 0) - lowest(body, 0)) <= 0.001f && abs(highest(slot.top, 0) - highest(body, 0)) <= 0.001f,
            )
        }
    }

    /** A failure means the lettering has other contours, or spans another x range than the art's BREAKER. */
    @Test
    fun `the lettering is twelve contours that span the expected x range`() {
        assertEquals("overlay: the letters expected 12 contours", 12, TileArt.letters.ops.count { it == TileArt.OP_MOVE })
        assertTrue("overlay: the letters expected a left edge at 332.5", abs(lowest(TileArt.letters, 0) - 332.5f) <= 0.01f)
        assertTrue("overlay: the letters expected a right edge at 692.87", abs(highest(TileArt.letters, 0) - 692.87f) <= 0.01f)
    }
}
