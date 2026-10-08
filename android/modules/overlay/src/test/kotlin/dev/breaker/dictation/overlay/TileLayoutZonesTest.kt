package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which zone a touch lands in, for each window shape: points inside every cell, on every edge (the left
 * and top edges are inside, the right and bottom edges are outside), outside the window, and a count of
 * every pixel's zone against the cell areas.
 */
class TileLayoutZonesTest {

    private val s = 100

    private fun zone(shape: TileShape, x: Int, y: Int): TileZone = TileLayout.zoneAt(x, y, shape, s)

    private fun check(shape: TileShape, expected: TileZone, vararg points: Pair<Int, Int>) {
        points.forEach { (x, y) ->
            assertEquals("overlay: $shape at ($x,$y) expected $expected", expected, zone(shape, x, y))
        }
    }

    // ---- collapsed ----

    /** A failure means the collapsed tile does not answer MIC for every pixel of the square, or answers for a pixel outside it. */
    @Test
    fun `collapsed tile is the mic inside the square and nothing outside it`() {
        check(TileShape.COLLAPSED, TileZone.MIC, 0 to 0, 99 to 99, 0 to 99, 99 to 0, 50 to 50)
        check(TileShape.COLLAPSED, TileZone.NONE, 100 to 50, 50 to 100, 100 to 100, -1 to 0, 0 to -1, -1 to -1, 150 to 100, 250 to 125, 50 to 120)
    }

    // ---- recording ----

    /** A failure means a point inside the cancel, mic or send cell, or on one of their edges, lands in the wrong zone while recording. */
    @Test
    fun `recording buttons and their half-open edges`() {
        check(TileShape.RECORDING, TileZone.CANCEL, 0 to 50, 99 to 149, 0 to 149, 99 to 50, 50 to 100)
        check(TileShape.RECORDING, TileZone.MIC, 100 to 50, 199 to 149, 100 to 149, 199 to 50, 150 to 100)
        check(TileShape.RECORDING, TileZone.SEND, 200 to 50, 299 to 149, 200 to 149, 299 to 50, 250 to 100)
    }

    /** A failure means the strip above the buttons, the meter's area included, is not the strip while recording, or its edges are off. */
    @Test
    fun `recording strip and the pixels around the window`() {
        check(TileShape.RECORDING, TileZone.STRIP, 0 to 0, 299 to 0, 0 to 49, 299 to 49, 150 to 0, 150 to 49, 100 to 49, 199 to 25, 50 to 25, 250 to 25)
        check(TileShape.RECORDING, TileZone.NONE, 300 to 100, 300 to 149, 300 to 50, 299 to 150, 0 to 150, 150 to 150, -1 to 100, 150 to -1, -1 to -1, 300 to 150, 300 to 0)
    }

    // ---- notice ----

    /** A failure means the notice window answers for the cancel or send cell, which are not drawn, or for the mic and strip wrongly. */
    @Test
    fun `notice window has a mic and a strip and nothing else`() {
        check(TileShape.NOTICE, TileZone.MIC, 100 to 50, 199 to 149, 100 to 149, 199 to 50, 150 to 100)
        check(TileShape.NOTICE, TileZone.STRIP, 0 to 0, 299 to 49, 0 to 49, 299 to 0, 150 to 25, 100 to 49, 199 to 49)
        check(TileShape.NOTICE, TileZone.NONE, 0 to 50, 50 to 100, 99 to 149, 99 to 50, 200 to 50, 250 to 100, 299 to 149, 300 to 25, 300 to 100, 150 to 150, -1 to 25, 150 to -1, 199 to 150)
    }

    // ---- an odd side ----

    /** A failure means the zones are wrong when the side is odd and the strip is half the side with the fraction dropped. */
    @Test
    fun `an odd tile side`() {
        // Side 101: strip 50, window 303 by 151, cells cancel 0..100, mic 101..201, send 202..302, all from y 50.
        assertEquals("overlay: odd side, last cancel pixel", TileZone.CANCEL, TileLayout.zoneAt(100, 150, TileShape.RECORDING, 101))
        assertEquals("overlay: odd side, first mic pixel", TileZone.MIC, TileLayout.zoneAt(101, 50, TileShape.RECORDING, 101))
        assertEquals("overlay: odd side, last mic pixel", TileZone.MIC, TileLayout.zoneAt(201, 150, TileShape.RECORDING, 101))
        assertEquals("overlay: odd side, first send pixel", TileZone.SEND, TileLayout.zoneAt(202, 50, TileShape.RECORDING, 101))
        assertEquals("overlay: odd side, last send pixel", TileZone.SEND, TileLayout.zoneAt(302, 150, TileShape.RECORDING, 101))
        assertEquals("overlay: odd side, past the right edge", TileZone.NONE, TileLayout.zoneAt(303, 150, TileShape.RECORDING, 101))
        assertEquals("overlay: odd side, past the bottom edge", TileZone.NONE, TileLayout.zoneAt(150, 151, TileShape.RECORDING, 101))
        assertEquals("overlay: odd side, last strip row", TileZone.STRIP, TileLayout.zoneAt(150, 49, TileShape.RECORDING, 101))
        assertEquals("overlay: odd side, collapsed, last pixel", TileZone.MIC, TileLayout.zoneAt(100, 100, TileShape.COLLAPSED, 101))
        assertEquals("overlay: odd side, collapsed, past the edge", TileZone.NONE, TileLayout.zoneAt(101, 100, TileShape.COLLAPSED, 101))
    }

    // ---- every pixel counted ----

    private fun counts(shape: TileShape, side: Int): Map<TileZone, Int> {
        val tally = HashMap<TileZone, Int>()
        for (x in -3 until 3 * side + 3) for (y in -3 until 2 * side + 3) {
            val z = TileLayout.zoneAt(x, y, shape, side)
            tally[z] = (tally[z] ?: 0) + 1
        }
        return tally
    }

    /** A failure means, for some small side, the number of pixels in a zone is not the area of its cells, so a cell is missing, doubled or shifted. */
    @Test
    fun `the pixels of each zone add up to the areas of the cells`() {
        for (side in 1..12) {
            val strip = side / 2
            val grid = (3 * side + 6) * (2 * side + 6)
            val collapsed = counts(TileShape.COLLAPSED, side)
            assertEquals("overlay: side $side collapsed mic pixels", side * side, collapsed[TileZone.MIC] ?: 0)
            assertEquals("overlay: side $side collapsed other pixels", grid - side * side, collapsed[TileZone.NONE] ?: 0)

            val notice = counts(TileShape.NOTICE, side)
            assertEquals("overlay: side $side notice mic pixels", side * side, notice[TileZone.MIC] ?: 0)
            assertEquals("overlay: side $side notice strip pixels", 3 * side * strip, notice[TileZone.STRIP] ?: 0)
            assertEquals("overlay: side $side notice cancel pixels", null, notice[TileZone.CANCEL])
            assertEquals("overlay: side $side notice send pixels", null, notice[TileZone.SEND])
            assertEquals("overlay: side $side notice other pixels", grid - side * side - 3 * side * strip, notice[TileZone.NONE] ?: 0)

            val recording = counts(TileShape.RECORDING, side)
            assertEquals("overlay: side $side recording mic pixels", side * side, recording[TileZone.MIC] ?: 0)
            assertEquals("overlay: side $side recording cancel pixels", side * side, recording[TileZone.CANCEL] ?: 0)
            assertEquals("overlay: side $side recording send pixels", side * side, recording[TileZone.SEND] ?: 0)
            assertEquals("overlay: side $side recording strip pixels", 3 * side * strip, recording[TileZone.STRIP] ?: 0)
            assertEquals("overlay: side $side recording other pixels", grid - 3 * side * side - 3 * side * strip, recording[TileZone.NONE] ?: 0)
        }
    }
}
