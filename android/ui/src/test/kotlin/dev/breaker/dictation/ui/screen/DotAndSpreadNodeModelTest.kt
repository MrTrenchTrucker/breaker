package dev.breaker.dictation.ui.screen

import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The defaults of the spread parameter of a box, and the dot node added to the node model. The
 * constructors that existed before still give the nodes they gave, and a dot is a leaf that carries
 * its slot and nothing else.
 */
class DotAndSpreadNodeModelTest {
    @Test
    fun `a box built without the spread parameter does not spread`() {
        val plain = Box("b", PaletteSlot.SURFACE, listOf(TrimStripe("rule")))

        assertFalse("no spread by default", plain.spread)
        assertEquals(
            "the box built without spread is the box built with spread false",
            Box("b", PaletteSlot.SURFACE, listOf(TrimStripe("rule")), spread = false),
            plain,
        )
    }

    @Test
    fun `a box with spread differs from the plain box and keeps its setting`() {
        val plain = Box("b", PaletteSlot.SURFACE, horizontal = true)
        val spread = Box("b", PaletteSlot.SURFACE, horizontal = true, spread = true)

        assertNotEquals("spread", plain, spread)
        assertTrue("the spread setting is kept", spread.spread)
    }

    @Test
    fun `a dot is equal when its id and slot are equal and differs in either`() {
        assertEquals(Dot("dot", PaletteSlot.ACCENT), Dot("dot", PaletteSlot.ACCENT))
        assertNotEquals("slot", Dot("dot", PaletteSlot.ACCENT), Dot("dot", PaletteSlot.TRIM))
        assertNotEquals("id", Dot("dot", PaletteSlot.ACCENT), Dot("other", PaletteSlot.ACCENT))
        assertEquals("the slot is kept", PaletteSlot.PRIMARY, Dot("dot", PaletteSlot.PRIMARY).color)
    }

    @Test
    fun `a dot flattens to itself and has no children to add`() {
        assertEquals(listOf("dot"), Dot("dot", PaletteSlot.PRIMARY).flatten().map { it.id })
    }

    @Test
    fun `a dot in a row comes before the word that follows it in the flatten order`() {
        val row = Box(
            "row",
            PaletteSlot.SURFACE,
            horizontal = true,
            spread = true,
            children = listOf(Dot("dot", PaletteSlot.ACCENT), TrimStripe("word")),
        )

        assertEquals(listOf("row", "dot", "word"), row.flatten().map { it.id })
    }
}
