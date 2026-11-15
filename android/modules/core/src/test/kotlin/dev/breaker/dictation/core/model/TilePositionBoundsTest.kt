package dev.breaker.dictation.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** A tile position is a pair of screen fractions: both edges are legal and anything outside is refused. */
class TilePositionBoundsTest {
    @Test
    fun `both edges of each axis are accepted`() {
        assertEquals(0f, TilePosition(x = 0f, y = 0f).y, 0f)
        assertEquals(1f, TilePosition(x = 1f, y = 1f).y, 0f)
        assertEquals(1f, TilePosition(x = 1f, y = 0f).x, 0f)
        assertEquals(1f, TilePosition(x = 0f, y = 1f).y, 0f)
    }

    @Test
    fun `a y just above one or just below zero is refused`() {
        assertThrows(IllegalArgumentException::class.java) { TilePosition(x = 0.5f, y = 1.0001f) }
        assertThrows(IllegalArgumentException::class.java) { TilePosition(x = 0.5f, y = -0.0001f) }
    }

    @Test
    fun `an x just above one or just below zero is refused`() {
        assertThrows(IllegalArgumentException::class.java) { TilePosition(x = 1.0001f, y = 0.5f) }
        assertThrows(IllegalArgumentException::class.java) { TilePosition(x = -0.0001f, y = 0.5f) }
    }
}
