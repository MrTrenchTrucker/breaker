package dev.breaker.dictation.ui.screen

import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The spacing names added to the node model: the defaults of the new box parameters, the spacer
 * node and its flatten, and the back intent. The constructors that existed before still give the
 * nodes they gave.
 */
class SpacingNodeModelTest {
    @Test
    fun `a box built without the spacing parameters has no padding and no gap`() {
        val plain = Box("b", PaletteSlot.SURFACE, listOf(TrimStripe("rule")))

        assertEquals("no padding by default", Space.NONE, plain.pad)
        assertEquals("no gap by default", Space.NONE, plain.gap)
        assertEquals(
            "the box built without the names is the box built with them as none",
            Box("b", PaletteSlot.SURFACE, listOf(TrimStripe("rule")), pad = Space.NONE, gap = Space.NONE),
            plain,
        )
    }

    @Test
    fun `a box with a pad or a gap differs from the plain box`() {
        val plain = Box("b", PaletteSlot.SURFACE)

        assertNotEquals("pad", plain, Box("b", PaletteSlot.SURFACE, pad = Space.M))
        assertNotEquals("gap", plain, Box("b", PaletteSlot.SURFACE, gap = Space.S))
        assertEquals("the pad is kept", Space.XL, Box("b", PaletteSlot.SURFACE, pad = Space.XL).pad)
    }

    @Test
    fun `a spacer is equal when its id and size are equal and differs in either`() {
        assertEquals(Spacer("gap", Space.M), Spacer("gap", Space.M))
        assertNotEquals("size", Spacer("gap", Space.M), Spacer("gap", Space.L))
        assertNotEquals("id", Spacer("gap", Space.M), Spacer("other", Space.M))
    }

    @Test
    fun `a spacer flattens to itself and has no children to add`() {
        assertEquals(listOf("gap"), Spacer("gap", Space.S).flatten().map { it.id })
    }

    @Test
    fun `spacers inside a box come after their siblings in the flatten order`() {
        val tree = Box("root", PaletteSlot.BACKGROUND, listOf(Spacer("a", Space.XS), Spacer("b", Space.XL)))

        assertEquals(listOf("root", "a", "b"), tree.flatten().map { it.id })
    }

    @Test
    fun `the spacing names are none and then the five steps in order`() {
        assertEquals(listOf("NONE", "XS", "S", "M", "L", "XL"), Space.entries.map { it.name })
    }

    @Test
    fun `back is a data object that equals itself and differs from the other intents`() {
        assertEquals(ScreenIntent.Back, ScreenIntent.Back)
        assertNotEquals("back", ScreenIntent.Back, ScreenIntent.ToggleTheme)
        assertNotEquals("back", ScreenIntent.Back, ScreenIntent.Home("CLOSE"))
    }
}
