package dev.breaker.dictation.ui.render

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The pure part of a spread row: how many separators it holds. A row with fewer than two children has
 * none, and a row of more has one fewer separator than it has children, one between each two.
 */
class SpreadSeparatorTest {
    @Test
    fun `a row with no child or with one child has no separator`() {
        assertEquals(0, spreadSeparatorCount(0))
        assertEquals(0, spreadSeparatorCount(1))
    }

    @Test
    fun `a row with two children has one separator between them`() {
        assertEquals(1, spreadSeparatorCount(2))
    }

    @Test
    fun `a row of n children has n minus one separators, one between each two`() {
        assertEquals(2, spreadSeparatorCount(3))
        assertEquals(4, spreadSeparatorCount(5))
        assertEquals(9, spreadSeparatorCount(10))
    }

    @Test
    fun `the separator count never goes below none`() {
        for (children in 0..1) {
            assertEquals("children $children", 0, spreadSeparatorCount(children))
        }
    }
}
