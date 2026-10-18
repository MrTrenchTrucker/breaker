package dev.breaker.dictation.ui.screen

import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The defaults of the parameters added to the node model, and the three nodes added to it. The
 * constructors that existed before still give the nodes they gave, so no screen built before
 * this change draws differently.
 */
class FoundationNodeModelTest {
    @Test
    fun `a box built without the new parameters has no outline, no edge bar and no tap`() {
        val plain = Box("b", PaletteSlot.SURFACE)

        assertNull("no outline by default", plain.outline)
        assertNull("no edge bar by default", plain.edge)
        assertNull("no tap by default", plain.onClick)
        assertFalse("a box stacks its children by default", plain.horizontal)
    }

    @Test
    fun `an outline, an edge bar or a tap makes a box different from the plain one`() {
        val plain = Box("b", PaletteSlot.SURFACE)

        assertNotEquals("outline", plain, Box("b", PaletteSlot.SURFACE, outline = PaletteSlot.ACCENT))
        assertNotEquals("edge", plain, Box("b", PaletteSlot.SURFACE, edge = PaletteSlot.ACCENT))
        assertNotEquals("tap", plain, Box("b", PaletteSlot.SURFACE, onClick = ScreenIntent.ToggleTheme))
        assertEquals("the outline is kept", PaletteSlot.TRIM, Box("b", PaletteSlot.SURFACE, outline = PaletteSlot.TRIM).outline)
    }

    @Test
    fun `a label is plain unless it asks for capitals or italics`() {
        val plain = Label("l", "Last transcript", TypeRole.BODY, PaletteSlot.TEXT)

        assertFalse("no capitals by default", plain.caps)
        assertFalse("no italics by default", plain.italic)
        assertNotEquals("caps", plain, Label("l", "Last transcript", TypeRole.BODY, PaletteSlot.TEXT, caps = true))
        assertNotEquals("italic", plain, Label("l", "Last transcript", TypeRole.BODY, PaletteSlot.TEXT, italic = true))
    }

    @Test
    fun `a label drawn in capitals keeps its text as written`() {
        assertEquals("the text is not changed", "Last transcript", Label("l", "Last transcript", TypeRole.BODY, PaletteSlot.TEXT, caps = true).text)
    }

    @Test
    fun `the three new nodes carry their ids and flatten to themselves`() {
        assertEquals(listOf("rule"), Rule("rule", PaletteSlot.ACCENT).flatten().map { it.id })
        assertEquals(listOf("stripes"), BottomStripes("stripes").flatten().map { it.id })
        assertEquals(listOf("back"), BackArrow("back", ScreenIntent.ToggleTheme).flatten().map { it.id })
    }

    @Test
    fun `a rule is equal when its id and slot are equal and differs in either`() {
        assertEquals(Rule("rule", PaletteSlot.ACCENT), Rule("rule", PaletteSlot.ACCENT))
        assertNotEquals("slot", Rule("rule", PaletteSlot.ACCENT), Rule("rule", PaletteSlot.TRIM))
        assertNotEquals("id", Rule("rule", PaletteSlot.ACCENT), Rule("other", PaletteSlot.ACCENT))
    }

    @Test
    fun `a back arrow keeps the intent it was given`() {
        assertEquals(ScreenIntent.Home("CLOSE"), BackArrow("back", ScreenIntent.Home("CLOSE")).intent)
    }

    @Test
    fun `the new nodes sit inside a box in order and flatten depth first`() {
        val tree = Box("root", PaletteSlot.BACKGROUND, listOf(Rule("rule", PaletteSlot.TRIM), BottomStripes("stripes")))

        assertEquals(listOf("root", "rule", "stripes"), tree.flatten().map { it.id })
    }

    @Test
    fun `the text look is the last kit look and the four before it keep their order`() {
        assertEquals(listOf("RAISED", "NEUTRAL", "DANGER", "OUTLINED", "TEXT"), KitLook.entries.map { it.name })
    }

    @Test
    fun `an action with a text look still reports its intent`() {
        val row = Action("row", "ABOUT", kit = KitLook.TEXT, intent = ScreenIntent.Home("ABOUT"))

        assertEquals(KitLook.TEXT, row.kit)
        assertEquals(ScreenIntent.Home("ABOUT"), row.intent)
    }
}
