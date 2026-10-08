package dev.breaker.dictation.ui.screen

import dev.breaker.dictation.ui.theme.PaletteSlot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The node model is what every screen builds and the renderer switches on. These tests pin
 * its defaults, its value equality, the exact set of node and intent kinds, and the
 * entries of the three enums, so a change to any of them is seen here before a screen or
 * the renderer misreads it.
 */
class NodeModelTest {
    private fun label(
        id: String = "l",
        text: String = "t",
        role: TypeRole = TypeRole.BODY,
        color: PaletteSlot = PaletteSlot.TEXT,
        align: TextAlign = TextAlign.START,
    ) = Label(id, text, role, color, align)

    private fun action(
        id: String = "a",
        text: String = "go",
        enabled: Boolean = true,
        emphasis: Emphasis = Emphasis.SECONDARY,
        intent: ScreenIntent = ScreenIntent.ToggleTheme,
    ) = Action(id, text, enabled, emphasis, intent)

    @Test
    fun `a box keeps its children in the order given and has none by default`() {
        val first = TrimStripe("first")
        val second = label("second")
        val box = Box("b", PaletteSlot.SURFACE, listOf(first, second))

        assertEquals(listOf<Node>(first, second), box.children)
        assertEquals(PaletteSlot.SURFACE, box.background)
        assertEquals(emptyList<Node>(), Box("empty", PaletteSlot.BACKGROUND).children)
    }

    @Test
    fun `a label is start aligned unless told otherwise`() {
        val plain = Label("l", "text", TypeRole.BODY, PaletteSlot.TEXT_MUTED)

        assertEquals(TextAlign.START, plain.align)
        assertEquals(TypeRole.BODY, plain.role)
        assertEquals(PaletteSlot.TEXT_MUTED, plain.color)
    }

    @Test
    fun `an action is enabled and secondary unless told otherwise`() {
        val plain = Action(id = "a", text = "go", intent = ScreenIntent.ToggleTheme)

        assertTrue("an action is enabled by default", plain.enabled)
        assertEquals(Emphasis.SECONDARY, plain.emphasis)
    }

    @Test
    fun `labels are equal when every field is equal and different when any one differs`() {
        assertEquals(label(), label())
        assertEquals(label().hashCode(), label().hashCode())
        assertNotEquals("id", label(), label(id = "other"))
        assertNotEquals("text", label(), label(text = "other"))
        assertNotEquals("role", label(), label(role = TypeRole.DISPLAY))
        assertNotEquals("color", label(), label(color = PaletteSlot.DANGER))
        assertNotEquals("align", label(), label(align = TextAlign.END))
    }

    @Test
    fun `actions are equal when every field is equal and different when any one differs`() {
        assertEquals(action(), action())
        assertEquals(action().hashCode(), action().hashCode())
        assertNotEquals("id", action(), action(id = "other"))
        assertNotEquals("text", action(), action(text = "other"))
        assertNotEquals("enabled", action(), action(enabled = false))
        assertNotEquals("emphasis", action(), action(emphasis = Emphasis.PRIMARY))
        assertNotEquals("intent", action(), action(intent = ScreenIntent.SetRoutingMode("LOCAL")))
    }

    @Test
    fun `boxes and screens compare by their children and nodes`() {
        val kids = listOf<Node>(label("one"))
        assertEquals(Box("b", PaletteSlot.SURFACE, kids), Box("b", PaletteSlot.SURFACE, listOf(label("one"))))
        assertNotEquals(Box("b", PaletteSlot.SURFACE, kids), Box("b", PaletteSlot.SURFACE, listOf(label("two"))))
        assertNotEquals(Box("b", PaletteSlot.SURFACE, kids), Box("b", PaletteSlot.BACKGROUND, kids))
        assertNotEquals("id", Box("a", PaletteSlot.SURFACE, kids), Box("b", PaletteSlot.SURFACE, kids))

        assertEquals(Screen("s", "Title", kids), Screen("s", "Title", listOf(label("one"))))
        assertNotEquals(Screen("s", "Title", kids), Screen("s", "Other", kids))
        assertNotEquals(Screen("s", "Title", kids), Screen("s", "Title", emptyList()))
        assertNotEquals("id", Screen("s1", "T", kids), Screen("s2", "T", kids))
    }

    @Test
    fun `trim stripes are equal when their ids are equal and different otherwise`() {
        assertEquals(TrimStripe("rule"), TrimStripe("rule"))
        assertEquals(TrimStripe("rule").hashCode(), TrimStripe("rule").hashCode())
        assertNotEquals(TrimStripe("rule"), TrimStripe("other"))
    }

    @Test
    fun `the five intents are distinct from one another`() {
        val intents = listOf<ScreenIntent>(
            ScreenIntent.ToggleTheme,
            ScreenIntent.UseSystemTheme,
            ScreenIntent.SetRoutingMode("LOCAL"),
            ScreenIntent.SetSetting("language", "de"),
            ScreenIntent.Setup("recheck"),
        )

        for (i in intents.indices) {
            for (j in intents.indices) {
                if (i == j) continue
                assertNotEquals("${intents[i]} must differ from ${intents[j]}", intents[i], intents[j])
            }
        }
    }

    @Test
    fun `an intent is equal only to the same intent with the same values`() {
        assertEquals(ScreenIntent.SetRoutingMode("LOCAL"), ScreenIntent.SetRoutingMode("LOCAL"))
        assertNotEquals(ScreenIntent.SetRoutingMode("LOCAL"), ScreenIntent.SetRoutingMode("SERVER"))
        assertEquals(ScreenIntent.SetSetting("language", "de"), ScreenIntent.SetSetting("language", "de"))
        assertNotEquals("key", ScreenIntent.SetSetting("language", "de"), ScreenIntent.SetSetting("modelSize", "de"))
        assertNotEquals("value", ScreenIntent.SetSetting("language", "de"), ScreenIntent.SetSetting("language", "fr"))
    }

    @Test
    fun `there are exactly six kinds of intent`() {
        assertEquals(
            setOf("ToggleTheme", "UseSystemTheme", "SetRoutingMode", "SetSetting", "Setup", "History"),
            permittedNames(ScreenIntent::class.java),
        )
    }

    @Test
    fun `there are exactly four kinds of node`() {
        assertEquals(
            setOf("Box", "Label", "Action", "TrimStripe"),
            permittedNames(Node::class.java),
        )
    }

    @Test
    fun `the type roles are the three the renderer switches on`() {
        assertEquals(listOf("DISPLAY", "BODY", "MONO"), TypeRole.entries.map { it.name })
    }

    @Test
    fun `the alignments are the three the renderer switches on`() {
        assertEquals(listOf("START", "CENTER", "END"), TextAlign.entries.map { it.name })
    }

    @Test
    fun `the emphases are the three the renderer switches on`() {
        assertEquals(listOf("PRIMARY", "SECONDARY", "DESTRUCTIVE"), Emphasis.entries.map { it.name })
    }

    @Test
    fun `flatten lists a node then its children depth first`() {
        val tree = Box(
            "root",
            PaletteSlot.BACKGROUND,
            listOf(
                Box("a", PaletteSlot.SURFACE, listOf(label("a1"), label("a2"))),
                label("b"),
            ),
        )

        assertEquals(listOf("root", "a", "a1", "a2", "b"), tree.flatten().map { it.id })
        assertEquals(listOf("only"), label("only").flatten().map { it.id })
    }

    private fun permittedNames(type: Class<*>): Set<String> {
        val permitted = type.permittedSubclasses ?: error("${type.simpleName} is not sealed")
        return permitted.map { it.simpleName }.toSet()
    }
}
