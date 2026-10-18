package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Checks the close target path data against the numbers measured from the idle close target art file.
 * Plain JVM: the data is plain Kotlin lists, and no Android type is used.
 */
class CloseTargetArtTest {

    private val truck = CloseTargetArt.TRUCK

    private fun numbersOf(cmd: PathCmd): List<Float> = when (cmd) {
        is MoveTo -> listOf(cmd.x, cmd.y)
        is LineTo -> listOf(cmd.x, cmd.y)
        is CurveTo -> listOf(cmd.x1, cmd.y1, cmd.x2, cmd.y2, cmd.x, cmd.y)
        is ClosePath -> emptyList()
    }

    private fun endPoint(cmd: PathCmd): Pair<Float, Float>? = when (cmd) {
        is MoveTo -> Pair(cmd.x, cmd.y)
        is LineTo -> Pair(cmd.x, cmd.y)
        is CurveTo -> Pair(cmd.x, cmd.y)
        is ClosePath -> null
    }

    private fun subPaths(commands: List<PathCmd>): List<List<PathCmd>> {
        val out = ArrayList<MutableList<PathCmd>>()
        for (cmd in commands) {
            if (cmd is MoveTo) {
                out.add(ArrayList())
            }
            out.last().add(cmd)
        }
        return out
    }

    private fun points(commands: List<PathCmd>): List<Pair<Float, Float>> =
        commands.flatMap { numbersOf(it).chunked(2) }.map { Pair(it[0], it[1]) }

    private fun signedArea2(sub: List<PathCmd>): Double {
        val pts = sub.mapNotNull { endPoint(it) }
        var sum = 0.0
        for (k in pts.indices) {
            val (x1, y1) = pts[k]
            val (x2, y2) = pts[(k + 1) % pts.size]
            sum += x1.toDouble() * y2.toDouble() - x2.toDouble() * y1.toDouble()
        }
        return sum
    }

    /** If this fails, the truck is not the 311 commands of the 31 sub-paths measured from the art. */
    @Test
    fun `truck has 311 commands of 31 move, 101 line, 148 curve and 31 close`() {
        assertEquals("overlay: the truck must have 311 commands", 311, truck.size)
        assertEquals("overlay: the truck must have 31 move commands", 31, truck.count { it is MoveTo })
        assertEquals("overlay: the truck must have 101 line commands", 101, truck.count { it is LineTo })
        assertEquals("overlay: the truck must have 148 curve commands", 148, truck.count { it is CurveTo })
        assertEquals("overlay: the truck must have 31 close commands", 31, truck.count { it is ClosePath })
    }

    /** If this fails, the two parts do not hold 190 and 121 commands, or they do not join into the truck in order. */
    @Test
    fun `the truck parts hold 190 and 121 commands and join into the truck in order`() {
        assertEquals("overlay: the first truck part must hold 190 commands", 190, CloseTargetTruckOne.COMMANDS.size)
        assertEquals("overlay: the second truck part must hold 121 commands", 121, CloseTargetTruckTwo.COMMANDS.size)
        assertEquals(
            "overlay: the two parts must join into the truck",
            CloseTargetTruckOne.COMMANDS + CloseTargetTruckTwo.COMMANDS,
            truck,
        )
        assertTrue("overlay: the first part must end with the close of sub-path 12", CloseTargetTruckOne.COMMANDS.last() is ClosePath)
        assertTrue("overlay: the second part must start a sub-path", CloseTargetTruckTwo.COMMANDS.first() is MoveTo)
    }

    /** If this fails, a sub-path does not start with a move or does not end with a close. */
    @Test
    fun `every truck sub-path starts with a move and ends with a close`() {
        val subs = subPaths(truck)
        assertEquals("overlay: the truck must have 31 sub-paths", 31, subs.size)
        for ((index, sub) in subs.withIndex()) {
            assertTrue("overlay: sub-path ${index + 1} must start with a move", sub.first() is MoveTo)
            assertTrue("overlay: sub-path ${index + 1} must end with a close", sub.last() is ClosePath)
        }
    }

    /** If this fails, the truck does not hold 1152 numbers, or their sum is not the measured 374508.980. */
    @Test
    fun `the truck numbers are 1152 and sum to the measured total`() {
        val numbers = truck.flatMap { numbersOf(it) }
        assertEquals("overlay: the truck must hold 1152 numbers", 1152, numbers.size)
        val sum = numbers.fold(0.0) { acc, value -> acc + value.toDouble() }
        assertEquals("overlay: the sum of the truck numbers must be 374508.980", 374508.980, sum, 0.5)
    }

    /** If this fails, the first command is not the measured move, or the second is not the measured curve. */
    @Test
    fun `the first and second truck commands are as measured`() {
        assertEquals("overlay: the first truck command", MoveTo(572.52f, 83.27f), truck[0])
        assertEquals(
            "overlay: the second truck command",
            CurveTo(573.17f, 83.26f, 578.55f, 83.22f, 578.62f, 83.18f),
            truck[1],
        )
    }

    /** If this fails, the last sub-path does not start at the measured point. */
    @Test
    fun `the last truck sub-path starts at the measured point`() {
        val last = subPaths(truck).last()
        assertEquals("overlay: the last sub-path must start at 665.2 142.44", MoveTo(665.2f, 142.44f), last.first())
    }

    /** If this fails, the bounding box of the truck is not the measured one. */
    @Test
    fun `the truck bounding box matches the measured x and y range`() {
        val pts = points(truck)
        assertEquals("overlay: truck min x", 55.0, pts.minOf { it.first.toDouble() }, 0.01)
        assertEquals("overlay: truck max x", 707.75, pts.maxOf { it.first.toDouble() }, 0.01)
        assertEquals("overlay: truck min y", 59.23, pts.minOf { it.second.toDouble() }, 0.01)
        assertEquals("overlay: truck max y", 168.0, pts.maxOf { it.second.toDouble() }, 0.01)
    }

    /** If this fails, the orientation of the sub-paths is not the measured 17 negative, 11 positive and 3 zero. */
    @Test
    fun `the truck sub-paths are 17 negative, 11 positive and 3 zero`() {
        val areas = subPaths(truck).map { signedArea2(it) }
        assertEquals("overlay: negative sub-paths", 17, areas.count { it < 0.0 })
        assertEquals("overlay: positive sub-paths", 11, areas.count { it > 0.0 })
        assertEquals("overlay: zero sub-paths", 3, areas.count { it == 0.0 })
    }

    /** If this fails, the straps behind the load are not exactly the measured commands. */
    @Test
    fun `the straps behind the load are exactly as measured`() {
        assertEquals(
            "overlay: the straps behind the load",
            listOf(
                MoveTo(252.7f, 105.1f),
                LineTo(180f, 146f),
                MoveTo(266.9f, 126.9f),
                LineTo(278f, 146f),
                MoveTo(325.3f, 105.1f),
                LineTo(398f, 146f),
                MoveTo(311.1f, 126.9f),
                LineTo(300f, 146f),
            ),
            CloseTargetArt.STRAPS_BEHIND,
        )
    }

    /** If this fails, the load is not exactly the two measured closed polygons. */
    @Test
    fun `the load is exactly the two measured closed polygons`() {
        assertEquals(
            "overlay: the load",
            listOf(
                MoveTo(345.5f, 45.9f),
                LineTo(200.9f, 140f),
                LineTo(244.9f, 140f),
                LineTo(358.5f, 66.1f),
                ClosePath,
                MoveTo(219.5f, 66.1f),
                LineTo(333.1f, 140f),
                LineTo(377.1f, 140f),
                LineTo(232.5f, 45.9f),
                ClosePath,
            ),
            CloseTargetArt.LOAD,
        )
    }

    /** If this fails, the straps in front of the load are not exactly the measured commands. */
    @Test
    fun `the straps in front of the load are exactly as measured`() {
        assertEquals(
            "overlay: the straps in front of the load",
            listOf(
                MoveTo(252.7f, 105.1f),
                LineTo(266.9f, 126.9f),
                MoveTo(325.3f, 105.1f),
                LineTo(311.1f, 126.9f),
            ),
            CloseTargetArt.STRAPS_OVER,
        )
    }

    /** If this fails, the strap stroke width is not the measured 3 art units. */
    @Test
    fun `the strap width is 3 art units`() {
        assertEquals("overlay: the strap width", 3f, CloseTargetArt.STRAP_ART, 0f)
    }

    /** If this fails, a truck, load or strap point lies outside the 740 by 200 canvas. */
    @Test
    fun `the truck, the load and the straps lie inside the canvas`() {
        val all = truck + CloseTargetArt.LOAD + CloseTargetArt.STRAPS_BEHIND + CloseTargetArt.STRAPS_OVER
        for ((x, y) in points(all)) {
            assertTrue("overlay: x $x must be inside 0 to 740", x >= 0f && x <= 740f)
            assertTrue("overlay: y $y must be inside 0 to 200", y >= 0f && y <= 200f)
        }
    }

    /** If this fails, the truck is not wholly inside the drawn box (the canvas less the 4 unit margin) with 20 units of room. */
    @Test
    fun `the truck sits wholly inside the drawn box with room`() {
        for ((x, y) in points(truck)) {
            assertTrue("overlay: truck x $x must be at least 24 (4 margin plus 20 room)", x >= 24f)
            assertTrue("overlay: truck x $x must be at most 716 (736 less 20 room)", x <= 716f)
            assertTrue("overlay: truck y $y must be at least 24 (4 margin plus 20 room)", y >= 24f)
            assertTrue("overlay: truck y $y must be at most 176 (196 less 20 room)", y <= 176f)
        }
    }
}
