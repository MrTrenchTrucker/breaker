package dev.breaker.dictation.ui.screen

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The touch gap is the empty block that balances the top bar's menu icon. These tests pin that
 * it is a leaf that carries only its id, and that its value equality follows that id alone.
 */
class TouchGapNodeTest {
    @Test
    fun `a touch gap carries its id and nothing else`() {
        assertEquals("gap", TouchGap("gap").id)
    }

    @Test
    fun `a touch gap is a leaf, so flatten lists only itself`() {
        assertEquals(listOf("gap"), TouchGap("gap").flatten().map { it.id })
        assertTrue("the flattened node is the gap", TouchGap("gap").flatten().single() is TouchGap)
    }

    @Test
    fun `two touch gaps are equal when their ids match and differ when they do not`() {
        assertEquals(TouchGap("gap"), TouchGap("gap"))
        assertNotEquals(TouchGap("gap"), TouchGap("other"))
    }
}
