package dev.breaker.dictation.overlay

import org.junit.Assert.assertEquals
import org.junit.Test

/** The screen-reader memory of the mic: hover enter/exit order and which child holds focus. */
class MicA11yStateTest {
    private val none = MicAccessibility.NO_ID

    /** A failure here means moving between two children does not exit the old one before it enters the new one. */
    @Test
    fun `hover moves exit the old child then enter the new one`() {
        val state = MicA11yState()
        assertEquals("android_overlay: first hover enters", listOf(1 to true), state.hoverTo(1))
        assertEquals("android_overlay: same child sends nothing", emptyList<Pair<Int, Boolean>>(), state.hoverTo(1))
        assertEquals("android_overlay: child to child exits then enters", listOf(1 to false, 2 to true), state.hoverTo(2))
        assertEquals("android_overlay: leaving exits", listOf(2 to false), state.hoverTo(none))
        assertEquals("android_overlay: nothing to nothing sends nothing", emptyList<Pair<Int, Boolean>>(), state.hoverTo(none))
    }

    /** A failure here means a child that never had focus can clear another child's focus, or reset keeps state. */
    @Test
    fun `focus is gained by one child and cleared only by that child`() {
        val state = MicA11yState()
        state.focus(1, true)
        assertEquals("android_overlay: child 1 holds focus", 1, state.focused)
        state.focus(2, false)
        assertEquals("android_overlay: child 2 cannot clear child 1", 1, state.focused)
        state.focus(1, false)
        assertEquals("android_overlay: child 1 clears its own focus", none, state.focused)
        state.focus(2, true)
        state.hoverTo(2)
        state.reset()
        assertEquals("android_overlay: reset drops focus", none, state.focused)
        assertEquals("android_overlay: reset drops hover, so the next hover enters", listOf(2 to true), state.hoverTo(2))
    }
}
